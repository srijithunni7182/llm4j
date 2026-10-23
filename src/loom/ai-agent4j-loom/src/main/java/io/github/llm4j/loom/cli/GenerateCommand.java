package io.github.llm4j.loom.cli;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.generate.CheckGate;
import io.github.llm4j.loom.generate.FidelityGate;
import io.github.llm4j.loom.generate.Gate;
import io.github.llm4j.loom.generate.GateResult;
import io.github.llm4j.loom.generate.Interview;
import io.github.llm4j.loom.generate.Plan;
import io.github.llm4j.loom.generate.PlanChecker;
import io.github.llm4j.loom.generate.PlanJson;
import io.github.llm4j.loom.generate.PlanMarkdown;
import io.github.llm4j.loom.generate.Planner;
import io.github.llm4j.loom.generate.PromptAssembly;
import io.github.llm4j.loom.generate.RepairLoop;
import io.github.llm4j.loom.generate.ReviewGate;
import io.github.llm4j.loom.generate.Spend;
import io.github.llm4j.loom.generate.Summary;
import io.github.llm4j.loom.generate.Transcript;
import io.github.llm4j.loom.generate.Workspace;
import io.github.llm4j.loom.generate.Writer;
import io.github.llm4j.loom.init.ProjectLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave generate}: a description becomes a plan, and a plan becomes a project that passes
 * the language's checks, or the developer is told which gate failed and why. Generation never calls
 * the agents it writes and sends nothing.
 *
 * <p>Exit codes: 0 done, 1 usage, 2 could not run, 3 a gate or the plan did not pass, 4 the spend
 * cap.
 */
@Command(
        name = "generate",
        description =
                "Turns a description into a plan (PLAN.md and plan.json) and a plan into a project that passes check, review and its own tests. --gate-only runs the same gates on any project.")
final class GenerateCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<description>",
            description = "What the workflow should do, in your words.")
    String description;

    @Option(
            names = "--out",
            description =
                    "Where the plan or the project goes (default: a folder named after the workflow).")
    File out;

    @Option(
            names = "--model",
            description = "The model that writes the plan (default: LOOM_GENERATE_MODEL).")
    String model;

    @Option(
            names = "--plan-only",
            description = "Stop at the plan: write plan.json and PLAN.md and nothing else.")
    boolean planOnly;

    @Option(
            names = "--from-plan",
            description = "Build from this (edited) plan.json instead of asking the model for one.")
    File fromPlan;

    @Option(
            names = "--gate-only",
            description = "Run the gates on an existing project folder and say what fails.")
    File gateOnly;

    @Option(
            names = "--allow-model",
            description = "A model the workflow's agents may use (repeatable; default: any).")
    Set<String> allowedModels = new LinkedHashSet<>();

    @Option(
            names = "--record",
            description =
                    "Save the model's side of this generation to a file (no request text, no keys).")
    File record;

    @Option(
            names = "--replay",
            description = "Answer from a recorded transcript instead of asking a model.")
    File replay;

    @Option(
            names = "--flat",
            description =
                    "A plain folder (main.loom, prompts/, tests/ beside each other) instead of the Maven layout.")
    boolean flat;

    @Option(
            names = "--no-ask",
            description =
                    "Do not ask questions; use the proposed answers and list them in the plan.")
    boolean noAsk;

    @Option(
            names = "--interactive",
            description = "Ask the questions and show the plan for a yes before building.")
    boolean interactive;

    @Option(names = "--yes", description = "Build without asking whether the plan is right.")
    boolean yes;

    @Option(
            names = "--context",
            description =
                    "Existing files the model may read (prompts, tools.loot, a sibling script). Given to the model as data.")
    List<File> context = new ArrayList<>();

    @Option(
            names = "--max-attempts",
            description = "Most repair rounds for one gate (default 4; 8 in all).",
            defaultValue = "4")
    int maxAttempts;

    @Option(
            names = "--max-cost",
            description =
                    "The most the generation may cost, in dollars (default 0.50; needs --prices to count).",
            defaultValue = "0.50")
    java.math.BigDecimal maxCost;

    @Option(
            names = "--max-tokens",
            description = "The most tokens the generation may use (default 200000).",
            defaultValue = "200000")
    long maxTokens;

    @Option(
            names = "--prices",
            description = "The price table that turns tokens into money for --max-cost.")
    File prices;

    @Option(
            names = "--gate-level",
            description =
                    "Findings in the validity, reliability and prompts lenses at or above this fail G2 (default medium; high findings always fail).",
            defaultValue = "medium")
    String gateLevel;

    @Option(
            names = "--keep-failing",
            description = "Leave a failing draft in <out>.draft for inspection.")
    boolean keepFailing;

    @Option(
            names = "--into-existing",
            description =
                    "Add the project to a folder that already holds files (nothing is replaced).")
    boolean intoExisting;

    @Option(names = "--format", description = "text (default) or json.", defaultValue = "text")
    String format;

    @Override
    public Integer call() {
        return generate(this, WeaveEnv.system());
    }

    static int generate(GenerateCommand c, WeaveEnv env) {
        if (!List.of("text", "json").contains(c.format)) {
            env.err().println("Error: --format takes text or json.");
            return 1;
        }
        if (c.gateOnly != null) return gateOnly(c, env);
        if (c.fromPlan == null && (c.description == null || c.description.isBlank())) {
            env.err()
                    .println(
                            "Error: say what the workflow should do (weave generate \"...\"), give --from-plan, or --gate-only <folder>.");
            return 1;
        }
        Set<String> allowed = c.allowedModels.isEmpty() ? null : c.allowedModels;
        Plan plan;
        Transcript.Recording recording = null;
        LLMClient client = null;
        String model = null;
        if (!(c.fromPlan != null && c.planOnly)) {
            try {
                model = c.replay != null ? "replay" : modelName(c);
                client = clientFor(c, env);
            } catch (IllegalArgumentException | java.io.IOException e) {
                env.err().println("Error: " + e.getMessage());
                return 2;
            }
            if (c.record != null) {
                recording = new Transcript.Recording(client);
                client = recording;
            }
        }
        if (c.fromPlan != null) {
            try {
                plan = PlanJson.read(Files.readString(c.fromPlan.toPath(), StandardCharsets.UTF_8));
            } catch (java.io.IOException | IllegalArgumentException e) {
                env.err().println("Error: " + c.fromPlan + ": " + e.getMessage());
                return 2;
            }
        } else {
            try {
                List<Plan.Answer> answers =
                        Interview.answers(
                                Interview.questions(client, model, c.description),
                                c.noAsk || !c.interactive
                                        ? null
                                        : q ->
                                                env.human()
                                                        .promptHuman(
                                                                q.question()
                                                                        + " ["
                                                                        + q.proposed()
                                                                        + "]"));
                plan = Planner.plan(client, model, c.description, answers, reference(), allowed);
            } catch (IllegalArgumentException | IllegalStateException e) {
                env.err().println("Error: " + e.getMessage());
                return 2;
            } catch (RuntimeException e) {
                env.err()
                        .println(
                                "Error: the model could not be asked: "
                                        + (e.getMessage() == null ? e : e.getMessage()));
                return 2;
            }
        }
        List<String> problems = PlanChecker.check(plan, allowed);
        Path target =
                (c.out != null ? c.out.toPath() : Path.of(slug(plan.name())))
                        .toAbsolutePath()
                        .normalize();
        try {
            if (c.planOnly || !problems.isEmpty()) writePlan(plan, target);
            if (recording != null) recording.save(c.record.toPath());
        } catch (java.io.IOException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        if (!problems.isEmpty()) {
            env.out()
                    .println(
                            "The plan has "
                                    + problems.size()
                                    + " problem"
                                    + (problems.size() == 1 ? "" : "s")
                                    + ", so nothing was built. It is in "
                                    + target.resolve("plan.json")
                                    + "; edit it and run weave generate --from-plan:");
            problems.forEach(p -> env.out().println("  ✗ " + p));
            return 3;
        }
        if (c.planOnly) {
            env.out()
                    .println(
                            "Plan written to "
                                    + target.resolve("PLAN.md")
                                    + " and "
                                    + target.resolve("plan.json")
                                    + ". Edit plan.json, then: weave generate --from-plan "
                                    + target.resolve("plan.json"));
            return 0;
        }
        return build(c, env, plan, target, client, model, recording);
    }

    private static int build(
            GenerateCommand c,
            WeaveEnv env,
            Plan plan,
            Path target,
            LLMClient client,
            String model,
            Transcript.Recording recording) {
        if (c.interactive && !c.yes) {
            env.out().println(PlanMarkdown.render(plan));
            String answer = env.human().promptHuman("Build this? (yes / edit / no)");
            String a = answer == null ? "no" : answer.strip().toLowerCase();
            if (a.startsWith("e")) {
                try {
                    writePlan(plan, target);
                } catch (java.io.IOException e) {
                    env.err().println("Error: " + e.getMessage());
                    return 2;
                }
                env.out()
                        .println(
                                "Edit "
                                        + target.resolve("plan.json")
                                        + ", then: weave generate --from-plan "
                                        + target.resolve("plan.json"));
                return 0;
            }
            if (!a.startsWith("y")) {
                env.out().println("Nothing was built.");
                return 0;
            }
        }
        io.github.llm4j.loom.review.model.Severity soft;
        try {
            soft = io.github.llm4j.loom.review.model.Severity.valueOf(c.gateLevel.toUpperCase());
        } catch (IllegalArgumentException e) {
            env.err().println("Error: --gate-level takes high, medium, low or info.");
            return 1;
        }
        Map<String, String> context = new LinkedHashMap<>();
        try {
            for (File f : c.context)
                context.put(f.getName(), Files.readString(f.toPath(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        io.github.llm4j.budget.PriceTable.Price price = null;
        if (c.prices != null) {
            try {
                price =
                        io.github.llm4j.budget.PriceTable.load(c.prices.toPath())
                                .price(model)
                                .orElse(null);
            } catch (java.io.IOException | RuntimeException e) {
                env.err().println("Error: cannot read the prices: " + e.getMessage());
                return 2;
            }
        }
        Spend spend = new Spend(c.maxTokens, c.maxCost, price);
        Map<String, String> planFiles = new LinkedHashMap<>();
        planFiles.put("plan.json", PlanJson.write(plan));
        planFiles.put("PLAN.md", PlanMarkdown.render(plan));
        planFiles.putAll(Writer.datasetStub(plan));
        List<Gate> gates =
                List.of(
                        new CheckGate(checker(env)),
                        new ReviewGate(GenerateReview.reviewer(env), soft),
                        new FidelityGate());
        Writer.Layout layout = c.flat ? Writer.Layout.FLAT : Writer.Layout.MAVEN;
        RepairLoop.Outcome outcome;
        try {
            outcome =
                    RepairLoop.run(
                            client,
                            model,
                            plan,
                            PromptAssembly.system(
                                    reference(),
                                    io.github.llm4j.loom.review.Reviews.catalog().all()),
                            PromptAssembly.write(plan, context),
                            planFiles,
                            gates,
                            new RepairLoop.Settings(
                                    c.maxAttempts,
                                    Math.max(8, c.maxAttempts),
                                    spend,
                                    layout,
                                    new Writer.Stamp(
                                            "weave generate " + WeaveCLI.versionNumber(),
                                            java.time.LocalDate.now(env.clock()).toString()),
                                    Path.of(System.getProperty("java.io.tmpdir"))));
            if (recording != null) recording.save(c.record.toPath());
        } catch (IllegalStateException | java.io.IOException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            env.err()
                    .println(
                            "Error: the model could not be asked: "
                                    + (e.getMessage() == null ? e : e.getMessage()));
            return 2;
        }
        try (Workspace ws = outcome.workspace()) {
            if (outcome.passed()) {
                Workspace.Committed done = ws.commit(target, c.intoExisting);
                if (!done.ok()) {
                    env.err()
                            .println(
                                    "Error: "
                                            + String.join("; ", done.conflicts())
                                            + ". Nothing was written.");
                    return 2;
                }
                env.out()
                        .print(
                                Summary.text(
                                        plan,
                                        target.toString(),
                                        done.written(),
                                        outcome.results(),
                                        List.of(),
                                        spend,
                                        outcome.attempts().size(),
                                        Summary.nextSteps(target.toString())));
                return 0;
            }
            env.out()
                    .println(
                            outcome.status() == RepairLoop.Status.CAP
                                    ? "✗ Stopped by the spend cap: " + outcome.stoppedBy()
                                    : "✗ The gates did not pass: " + outcome.stoppedBy());
            for (GateResult g : outcome.results()) {
                if (g.passed()) continue;
                env.out().println("  " + g.gate() + " " + g.name() + " failed:");
                g.problems().forEach(p -> env.out().println("    " + p.text()));
            }
            if (ws != null && c.keepFailing) {
                Path draft = Path.of(target + ".draft");
                Workspace.Committed kept = ws.commit(draft, true);
                env.out()
                        .println(
                                kept.ok()
                                        ? "The failing draft is in " + draft
                                        : "The draft could not be kept: "
                                                + String.join("; ", kept.conflicts()));
            } else if (ws == null) env.out().println("Nothing usable was written.");
            else
                env.out()
                        .println(
                                "The best draft was discarded; run again with --keep-failing to keep it for inspection.");
            return outcome.status() == RepairLoop.Status.CAP ? 4 : 3;
        } catch (java.io.IOException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static String modelName(GenerateCommand c) {
        String m = c.model != null ? c.model : System.getenv("LOOM_GENERATE_MODEL");
        if (m == null || m.isBlank())
            throw new IllegalArgumentException(
                    "no model to write with: give --model or set LOOM_GENERATE_MODEL");
        return m;
    }

    private static LLMClient clientFor(GenerateCommand c, WeaveEnv env) throws java.io.IOException {
        if (c.replay != null) return Transcript.Replay.read(c.replay.toPath());
        return env.models().createClient(modelName(c));
    }

    /** The language as the model is shown it: the persona that writes Loom, then the reference. */
    private static String reference() {
        return resource("guide/LOOM_PROMPT.md") + "\n\n" + resource("guide/llms.txt");
    }

    private static String resource(String name) {
        try (var in = GenerateCommand.class.getClassLoader().getResourceAsStream(name)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static void writePlan(Plan plan, Path dir) throws java.io.IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("plan.json"), PlanJson.write(plan), StandardCharsets.UTF_8);
        Files.writeString(
                dir.resolve("PLAN.md"), PlanMarkdown.render(plan), StandardCharsets.UTF_8);
    }

    static String slug(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-");
    }

    // ---- gates on any project

    static List<Gate> gates(WeaveEnv env) {
        return List.of(new CheckGate(checker(env)), new FidelityGate());
    }

    static CheckGate.Checker checker(WeaveEnv env) {
        return (script, loot) -> {
            List<GateResult.Problem> out = new ArrayList<>();
            io.github.llm4j.loom.ast.LoomScript loaded;
            try {
                loaded = new io.github.llm4j.loom.execution.LoomLoader().load(script.toString());
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                var line = java.util.regex.Pattern.compile("line (\\d+)").matcher(m);
                out.add(
                        new GateResult.Problem(
                                "", line.find() ? Integer.parseInt(line.group(1)) : 0, "", m));
                return out;
            }
            WeaveCLI.Validation v =
                    WeaveCLI.validate(
                            script.toFile(),
                            loot == null ? null : loot.toFile(),
                            new WeaveCLI.CheckSettings(false, false, true, false),
                            env,
                            loaded);
            for (String r : v.reachProblems()) out.add(new GateResult.Problem("", 0, "", r));
            for (var p : v.problems())
                if (p.severity() == io.github.llm4j.loom.execution.ScriptValidator.Severity.ERROR)
                    out.add(
                            new GateResult.Problem(
                                    "",
                                    p.line(),
                                    "",
                                    (p.construct() == null ? "" : p.construct() + ": ")
                                            + p.message()));
            return out;
        };
    }

    private static int gateOnly(GenerateCommand c, WeaveEnv env) {
        Path given = c.gateOnly.toPath().toAbsolutePath().normalize();
        // a project folder, or a script (its folder is the project)
        boolean isScript = Files.isRegularFile(given) && given.toString().endsWith(".loom");
        Path dir = isScript ? given.getParent() : given;
        if (!isScript && !Files.isDirectory(dir)) {
            env.err().println("Error: " + dir + " is not a folder or a .loom script.");
            return 2;
        }
        Path script = isScript ? given : ProjectLayout.script(dir);
        if (script == null) {
            env.err()
                    .println(
                            "Error: "
                                    + dir
                                    + " has no main.loom (or src/main/resources/main.loom).");
            return 2;
        }
        Plan plan = null;
        Path planFile = dir.resolve("plan.json");
        if (Files.isRegularFile(planFile)) {
            try {
                plan = PlanJson.read(Files.readString(planFile, StandardCharsets.UTF_8));
            } catch (java.io.IOException | IllegalArgumentException e) {
                env.err().println("Error: " + planFile + ": " + e.getMessage());
                return 2;
            }
        }
        List<GateResult> results = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try (Workspace ws = Workspace.existing(dir, script)) {
            for (Gate g : gates(env)) {
                if (g.id().equals("G5") && plan == null) {
                    notes.add(
                            "G5 (plan fidelity) was skipped: there is no plan.json, as in a hand-written project.");
                    continue;
                }
                results.add(g.run(ws, plan == null ? emptyPlan() : plan));
            }
        } catch (java.io.IOException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        boolean passed = results.stream().allMatch(GateResult::passed);
        if (c.format.equals("json")) {
            env.out().println(json(dir, results, notes));
        } else {
            for (GateResult r : results) {
                env.out().println((r.passed() ? "✓ " : "✗ ") + r.gate() + " " + r.name());
                r.problems().forEach(p -> env.out().println("    " + p.text()));
            }
            notes.forEach(n -> env.out().println("ℹ " + n));
            env.out()
                    .println(
                            passed
                                    ? "All the gates that could run passed."
                                    : "A gate did not pass.");
        }
        return passed ? 0 : 3;
    }

    private static Plan emptyPlan() {
        return new Plan(
                Plan.VERSION,
                "Unplanned",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                "",
                List.of(),
                false);
    }

    private static String json(Path dir, List<GateResult> results, List<String> notes) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("version", 1).put("project", dir.toString());
        root.put("passed", results.stream().allMatch(GateResult::passed));
        var gates = root.putArray("gates");
        for (GateResult r : results) {
            var g = gates.addObject();
            g.put("gate", r.gate()).put("name", r.name()).put("passed", r.passed());
            var ps = g.putArray("problems");
            for (GateResult.Problem p : r.problems())
                ps.addObject()
                        .put("file", p.file())
                        .put("line", p.line())
                        .put("rule", p.rule())
                        .put("message", p.message());
        }
        var ns = root.putArray("notes");
        notes.forEach(ns::add);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
