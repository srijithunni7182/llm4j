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
                    script);
        }
        WeaveEnv withKeys = new EnvFileOptions().apply(env, script);
        if (withKeys == null) return 2;
        ProjectAdvisor.CheckResult check = check(script, withKeys);
        AuditReport audit = SecurityAudit.audit(loaded, file);
        return print(env, file, ProjectAdvisor.advise(script, loaded, check, audit), c.format, script);
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
    private static ProjectAdvisor.CheckResult check(Path script, WeaveEnv env) {
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

    private static int print(
            WeaveEnv env,
            String file,
            List<ProjectAdvisor.Step> steps,
            String format,
            Path script) {
        if (format.equals("json")) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("script", file);
            List<java.util.Map<String, Object>> rows = new ArrayList<>();
            for (ProjectAdvisor.Step st : steps) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("what", st.what());
                row.put("why", st.why());
                row.put("command", st.command());
                rows.add(row);
            }
            m.put("steps", rows);
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
        env.out().println("\nEverything before the last step is free: nothing here calls a model.");
        return 0;
    }
}
