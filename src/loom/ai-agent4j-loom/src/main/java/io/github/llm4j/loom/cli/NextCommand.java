package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.security.AuditReport;
import io.github.llm4j.loom.security.SecurityAudit;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code weave next}: what to do next in this project, from the files in it and the free checks. No
 * model is called and no key is read. It never changes anything; it says what to run or edit, in
 * the order the guide works.
 */
@Command(
        name = "next",
        description =
                "Looks at this project and says what to do next, in order, with the command for each step. Free: it calls no model and reads no key.")
final class NextCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<folder or script>",
            description = "The project (default: here).")
    File where;

    @picocli.CommandLine.Option(
            names = "--format",
            paramLabel = "text|json",
            description = "The steps as text (default) or as JSON, for a coding agent or the editor.",
            defaultValue = "text")
    String format;

    @picocli.CommandLine.Option(
            names = "--confirm",
            paramLabel = "<step>",
            description =
                    "Record that a person did a step no command can check. Takes real-run: they read one real answer and said what is right and wrong with it.")
    String confirm;

    @picocli.CommandLine.Option(
            names = "--skip",
            paramLabel = "<stage>",
            description =
                    "Record that you chose to skip a stage, with --because. The journey shows it as skipped with what it loses, until --unskip. A skip never hides a failing or finished stage.")
    String skip;

    @picocli.CommandLine.Option(
            names = "--because",
            paramLabel = "<reason>",
            description = "Why the stage is skipped (required with --skip); it is shown with the skip.")
    String because;

    @picocli.CommandLine.Option(
            names = "--unskip",
            paramLabel = "<stage>",
            description = "Remove a recorded skip.")
    String unskip;

    @Override
    public Integer call() {
        return next(this, WeaveEnv.system());
    }

    static int next(NextCommand c, WeaveEnv env) {
        StringBuilder why = new StringBuilder();
        Path script =
                ProjectAdvisor.find(
                        (c.where == null ? new File(".") : c.where)
                                .toPath()
                                .toAbsolutePath()
                                .normalize(),
                        why);
        if (script == null) {
            env.err().println(why);
            return 2;
        }
        if (!c.format.equals("text") && !c.format.equals("json")) {
            env.err().println("Error: --format takes text or json, got " + c.format + ".");
            return 2;
        }
        if (c.skip != null || c.unskip != null) return skipOrUnskip(c, env, script);
        if (c.confirm != null) {
            if (!c.confirm.equals(RealRunRead.STEP)) {
                env.err().println("Error: --confirm takes " + RealRunRead.STEP + ", got " + c.confirm + ".");
                return 2;
            }
            try {
                RealRunRead.confirm(io.github.llm4j.loom.init.ProjectLayout.root(script), script, env.clock());
            } catch (java.io.IOException e) {
                env.err().println("Error: could not record it: " + e.getMessage());
                return 1;
            }
            env.out().println("Recorded: a real answer of " + script.getFileName() + " was read.");
            return 0;
        }
        String file = script.getFileName().toString();
        LoomScript loaded = null;
        try {
            loaded = new LoomLoader().load(script.toString());
        } catch (Exception e) {
            return print(
                    env,
                    file,
                    ProjectAdvisor.advise(
                            script,
                            null,
                            new ProjectAdvisor.CheckResult(
                                    false,
                                    List.of(String.valueOf(e.getMessage())),
                                    List.of(),
                                    Set.of()),
                            null),
                    c.format,
                    script,
                    JourneyStages.journey(
                            script,
                            null,
                            new ProjectAdvisor.CheckResult(false, List.of(String.valueOf(e.getMessage())), List.of(), Set.of()),
                            null));
        }
        WeaveEnv withKeys = new EnvFileOptions().apply(env, script);
        if (withKeys == null) return 2;
        ProjectAdvisor.CheckResult check = check(script, withKeys);
        AuditReport audit = SecurityAudit.audit(loaded, file);
        return print(
                env,
                file,
                ProjectAdvisor.advise(script, loaded, check, audit),
                c.format,
                script,
                JourneyStages.journey(script, loaded, check, audit));
    }

    private static int skipOrUnskip(NextCommand c, WeaveEnv env, Path script) {
        if (c.skip != null && c.unskip != null) {
            env.err().println("Error: give --skip or --unskip, not both.");
            return 2;
        }
        String stage = c.skip != null ? c.skip : c.unskip;
        if (!JourneyStages.IDS.contains(stage)) {
            env.err().println("Error: no stage " + stage + ". The stages are: " + String.join(", ", JourneyStages.IDS) + ".");
            return 2;
        }
        Path root = io.github.llm4j.loom.init.ProjectLayout.root(script);
        try {
            if (c.unskip != null) {
                boolean fromReadme = JourneyStages.EVALUATION_STAGES.contains(stage) && unmarkEvaluationSkipped(root, env);
                boolean removed = SkippedStages.unskip(root, stage) || fromReadme;
                env.out().println(removed ? "No longer skipped: " + stage + "." : stage + " was not skipped.");
                return 0;
            }
            if (c.because == null || c.because.isBlank()) {
                env.err().println("Error: --skip needs --because \"<reason>\": a skip is a choice someone can read back later.");
                return 2;
            }
            String by = java.util.Objects.requireNonNullElse(env.env().apply("USER"), java.util.Objects.requireNonNullElse(env.env().apply("USERNAME"), "unknown"));
            SkippedStages.skip(root, stage, c.because, by, env.clock().instant());
            env.out().println("Skipped: " + stage + ". What this loses: " + StageTexts.of(stage).missing());
            if (SkippedStages.read(root).skips().keySet().containsAll(JourneyStages.EVALUATION_STAGES)) markEvaluationSkipped(root);
            return 0;
        } catch (java.io.IOException e) {
            env.err().println("Error: could not record it: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Takes one evaluation stage back from the README line {@code Evaluation: skipped}, which stands for all three: the line
     * is removed, and the other two stay skipped as entries of the skip list with the line as their reason. False when the
     * README has no such line.
     */
    private static boolean unmarkEvaluationSkipped(Path root, WeaveEnv env) throws java.io.IOException {
        Path readme = root.resolve("README.md");
        if (!java.nio.file.Files.isRegularFile(readme)) return false;
        List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(readme));
        String marker = lines.stream().filter(l -> l.contains("Evaluation: skipped")).findFirst().orElse(null);
        if (marker == null) return false;
        var recorded = SkippedStages.read(root).skips();
        for (String other : JourneyStages.EVALUATION_STAGES)
            if (!recorded.containsKey(other))
                SkippedStages.skip(root, other, "README said: " + marker.strip(), "README.md", env.clock().instant());
        lines.removeIf(l -> l.contains("Evaluation: skipped"));
        java.nio.file.Files.write(readme, lines);
        return true;
    }

    /** Keeps the README line the skills already read, once all three evaluation stages are skipped. */
    private static void markEvaluationSkipped(Path root) throws java.io.IOException {
        Path readme = root.resolve("README.md");
        String text = java.nio.file.Files.isRegularFile(readme) ? java.nio.file.Files.readString(readme) : "";
        if (text.contains("Evaluation: skipped")) return;
        java.nio.file.Files.writeString(readme, text + (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + "\nEvaluation: skipped\n");
    }

    /**
     * The first {@code limit} things {@code weave next} would say about a project, each as one line
     * with its command; empty when the project cannot be read.
     */
    static List<String> briefly(Path folder, WeaveEnv env, int limit) {
        try {
            Path script =
                    ProjectAdvisor.find(folder.toAbsolutePath().normalize(), new StringBuilder());
            if (script == null) return List.of();
            LoomScript loaded = new LoomLoader().load(script.toString());
            WeaveEnv withKeys = new EnvFileOptions().apply(env, script);
            if (withKeys == null) return List.of();
            List<String> out = new ArrayList<>();
            for (ProjectAdvisor.Step st :
                    ProjectAdvisor.advise(
                            script,
                            loaded,
                            check(script, withKeys),
                            SecurityAudit.audit(loaded, script.getFileName().toString()))) {
                if (out.size() >= limit) break;
                out.add(st.what() + (st.command() == null ? "" : "   $ " + st.command()));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The script's own check with no keys, read from its JSON form so the two never disagree. */
    static ProjectAdvisor.CheckResult check(Path script, WeaveEnv env) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        WeaveEnv quiet =
                new WeaveEnv(
                        env.models(),
                        env.human(),
                        new PrintStream(buffer, true, StandardCharsets.UTF_8),
                        env.err(),
                        env.clock(),
                        env.sleeper(),
                        env.commands(),
                        env.weave(),
                        env.env(),
                        env.askVia(),
                        env.secrets(),
                        env.prompts(),
                        env.observe(),
                        env.invoker());
        WeaveCLI.check(
                script.toFile(), null, new WeaveCLI.CheckSettings(false, true, true, false), quiet);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        try {
            JsonNode root = new ObjectMapper().readTree(buffer.toString(StandardCharsets.UTF_8));
            for (JsonNode p : root.path("diagnostics")) {
                String line =
                        p.path("line").asInt() > 0 ? "line " + p.path("line").asInt() + ": " : "";
                (p.path("severity").asText().equals("error") ? errors : warnings)
                        .add(line + p.path("message").asText());
            }
            root.path("notSetYet").forEach(n -> keys.add(n.asText()));
        } catch (java.io.IOException e) {
            errors.add("the check gave no readable answer");
        }
        return new ProjectAdvisor.CheckResult(true, errors, warnings, keys);
    }

    private static java.util.Map<String, Object> stageJson(JourneyStages.Stage st) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("number", st.number());
        row.put("id", st.id());
        row.put("title", st.title());
        row.put("state", st.state());
        row.put("detail", st.detail());
        row.put("command", st.command());
        StageTexts texts = StageTexts.of(st.id());
        row.put("why", texts.why());
        row.put("see", texts.see());
        row.put("decision", texts.decision());
        row.put("missing", texts.missing());
        row.put("evidence", st.evidence());
        return row;
    }

    static java.util.Map<String, Object> stageJson(JourneyStages.Stage st, JourneyStages.Journey journey) {
        java.util.Map<String, Object> row = stageJson(st);
        SkippedStages.Skip skip = journey.skips().get(st.id());
        if (skip != null && st.state().equals(JourneyStages.SKIPPED)) {
            java.util.Map<String, Object> k = new java.util.LinkedHashMap<>();
            k.put("because", skip.because());
            k.put("by", skip.by());
            k.put("at", skip.at());
            k.put("missing", StageTexts.of(st.id()).missing());
            row.put("skip", k);
        }
        return row;
    }

    private static int print(
            WeaveEnv env,
            String file,
            List<ProjectAdvisor.Step> steps,
            String format,
            Path script,
            JourneyStages.Journey journey) {
        List<JourneyStages.Stage> stages = journey.stages();
        if (format.equals("json")) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("script", file);
            m.put("projectRoot", io.github.llm4j.loom.init.ProjectLayout.root(script).toString());
            List<java.util.Map<String, Object>> rows = new ArrayList<>();
            for (ProjectAdvisor.Step st : steps) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("what", st.what());
                row.put("why", st.why());
                row.put("command", st.command());
                rows.add(row);
            }
            m.put("steps", rows);
            m.put("stages", stages.stream().map(st -> stageJson(st, journey)).toList());
            m.put("warnings", journey.warnings());
            m.put("primary", JourneyStages.primary(stages).map(NextCommand::stageJson).orElse(null));
            PreflightState pre = PreflightState.read(io.github.llm4j.loom.init.ProjectLayout.root(script), script);
            java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
            p.put("state", pre.state());
            if (pre.at() != null) p.put("at", pre.at());
            m.put("preflight", p);
            m.put("realRun", java.util.Map.of("confirmed", RealRunRead.confirmed(io.github.llm4j.loom.init.ProjectLayout.root(script), script)));
            try {
                env.out().println(new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(m));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            return 0;
        }
        env.out().println("weave next: " + file);
        int n = 0;
        for (ProjectAdvisor.Step s : steps) {
            n++;
            env.out()
                    .println(
                            (n == 1 ? "\nNow\n" : n == 2 ? "\nThen\n" : "")
                                    + "  "
                                    + n
                                    + ". "
                                    + s.what());
            env.out().println("     " + s.why());
            if (s.command() != null) env.out().println("     $ " + s.command());
        }
        List<JourneyStages.Stage> skipped = stages.stream().filter(st -> journey.skips().containsKey(st.id()) && st.state().equals(JourneyStages.SKIPPED)).toList();
        if (!skipped.isEmpty()) {
            env.out().println("\nSkipped, by your choice (weave next --unskip <stage> to take one back)");
            for (JourneyStages.Stage st : skipped)
                env.out().println("  " + st.id() + ": " + journey.skips().get(st.id()).because() + "\n     loses: " + StageTexts.of(st.id()).missing());
        }
        for (String w : journey.warnings()) env.out().println("\nWarning: " + w);
        env.out().println("\nEverything before the last step is free: nothing here calls a model.");
        return 0;
    }
}
