package io.github.llm4j.loom.cli;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.generate.CheckGate;
import io.github.llm4j.loom.generate.FidelityGate;
import io.github.llm4j.loom.generate.Gate;
import io.github.llm4j.loom.generate.GateResult;
import io.github.llm4j.loom.generate.Plan;
import io.github.llm4j.loom.generate.PlanChecker;
import io.github.llm4j.loom.generate.PlanJson;
import io.github.llm4j.loom.generate.PlanMarkdown;
import io.github.llm4j.loom.generate.Planner;
import io.github.llm4j.loom.generate.Transcript;
import io.github.llm4j.loom.generate.Workspace;
import io.github.llm4j.loom.init.ProjectLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        if (c.fromPlan != null) {
            try {
                plan = PlanJson.read(Files.readString(c.fromPlan.toPath(), StandardCharsets.UTF_8));
            } catch (java.io.IOException | IllegalArgumentException e) {
                env.err().println("Error: " + c.fromPlan + ": " + e.getMessage());
                return 2;
            }
        } else {
            LLMClient client;
            try {
                client = clientFor(c, env);
            } catch (IllegalArgumentException | java.io.IOException e) {
                env.err().println("Error: " + e.getMessage());
                return 2;
            }
            if (c.record != null) {
                recording = new Transcript.Recording(client);
                client = recording;
            }
            try {
                plan =
                        Planner.plan(
                                client,
                                c.replay != null ? "replay" : modelName(c),
                                c.description,
                                List.of(),
                                reference(),
                                allowed);
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
        env.out()
                .println(
                        "The plan is sound ("
                                + plan.agents().size()
                                + " agents, "
                                + plan.tools().size()
                                + " tools). Building the project from it comes next.");
        return 0;
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

    private static String reference() {
        try (var in =
                GenerateCommand.class.getClassLoader().getResourceAsStream("guide/llms.txt")) {
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
