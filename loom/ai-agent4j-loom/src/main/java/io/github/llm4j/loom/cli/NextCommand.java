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
 * {@code weave next}: what to do next in this project, from the files in it and the free checks. No model is called and no key is read. It
 * never changes anything; it says what to run or edit, in the order the guide works.
 */
@Command(name = "next", description = "Looks at this project and says what to do next, in order, with the command for each step. Free: it calls no model and reads no key.")
final class NextCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<folder or script>", description = "The project (default: here).")
    File where;

    @Override
    public Integer call() {
        return next(this, WeaveEnv.system());
    }

    static int next(NextCommand c, WeaveEnv env) {
        StringBuilder why = new StringBuilder();
        Path script = ProjectAdvisor.find((c.where == null ? new File(".") : c.where).toPath().toAbsolutePath().normalize(), why);
        if (script == null) {
            env.err().println("Error: " + why);
            return 2;
        }
        String file = script.getFileName().toString();
        LoomScript loaded = null;
        try {
            loaded = new LoomLoader().load(script.toString());
        } catch (Exception e) {
            return print(env, file, ProjectAdvisor.advise(script, null, new ProjectAdvisor.CheckResult(false, List.of(String.valueOf(e.getMessage())), List.of(), Set.of()), null));
        }
        WeaveEnv withKeys = new EnvFileOptions().apply(env, script);
        if (withKeys == null) return 2;
        ProjectAdvisor.CheckResult check = check(script, withKeys);
        AuditReport audit = SecurityAudit.audit(loaded, file);
        return print(env, file, ProjectAdvisor.advise(script, loaded, check, audit));
    }

    /** The script's own check with no keys, read from its JSON form so the two never disagree. */
    private static ProjectAdvisor.CheckResult check(Path script, WeaveEnv env) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        WeaveEnv quiet = new WeaveEnv(env.models(), env.human(), new PrintStream(buffer, true, StandardCharsets.UTF_8), env.err(), env.clock(), env.sleeper(),
                env.commands(), env.weave(), env.env(), env.askVia(), env.secrets(), env.prompts());
        WeaveCLI.check(script.toFile(), null, new WeaveCLI.CheckSettings(false, true, true, false), quiet);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        try {
            JsonNode root = new ObjectMapper().readTree(buffer.toString(StandardCharsets.UTF_8));
            for (JsonNode p : root.path("diagnostics")) {
                String line = p.path("line").asInt() > 0 ? "line " + p.path("line").asInt() + ": " : "";
                (p.path("severity").asText().equals("error") ? errors : warnings).add(line + p.path("message").asText());
            }
            root.path("notSetYet").forEach(n -> keys.add(n.asText()));
        } catch (java.io.IOException e) {
            errors.add("the check gave no readable answer");
        }
        return new ProjectAdvisor.CheckResult(true, errors, warnings, keys);
    }

    private static int print(WeaveEnv env, String file, List<ProjectAdvisor.Step> steps) {
        env.out().println("weave next: " + file);
        int n = 0;
        for (ProjectAdvisor.Step s : steps) {
            n++;
            env.out().println((n == 1 ? "\nNow\n" : n == 2 ? "\nThen\n" : "") + "  " + n + ". " + s.what());
            env.out().println("     " + s.why());
            if (s.command() != null) env.out().println("     $ " + s.command());
        }
        env.out().println("\nEverything before the last step is free: nothing here calls a model.");
        return 0;
    }
}
