package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.LootLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "weave",
        mixinStandardHelpOptions = true,
        versionProvider = WeaveCLI.BuildVersion.class,
        description = "Loom Orchestration CLI - Weave workflows into executable reality.",
        footer = {
            "",
            "weave man lists every command by what you are doing; weave man <command> shows its page."
        })
public class WeaveCLI implements Callable<Integer> {

    /**
     * {@code -v} gives the INFO lines, {@code -vv} the FINE ones; the answer is printed either way.
     */
    @CommandLine.Option(
            names = {"-v", "--verbose"},
            scope = CommandLine.ScopeType.INHERIT,
            description =
                    "Show the log as well as the answer: -v the progress lines, -vv the detail. By default only the answer is printed.")
    boolean[] verbosity = new boolean[0];

    @Override
    public Integer call() throws Exception {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "run", description = "Executes a .loom script immediately.")
    static class RunCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The .loom script file to execute.")
        private File scriptFile;

        @Option(
                names = {"-l", "--loot"},
                description = "The .loot tool mapping file.")
        private File lootFile;

        @Option(
                names = {"-w", "--workflow"},
                description = "The name of the workflow to run.",
                defaultValue = "Main")
        private String workflowName;

        @Option(
                names = {"-i", "--input"},
                description = "Initial context variables in key=value format.")
        private Map<String, String> inputs = new HashMap<>();

        @Option(
                names = "--max-tokens",
                description = "Cap the run's tokens (replaces the script's run budget).")
        private Long maxTokens;

        @Option(
                names = "--max-calls",
                description = "Cap the run's LLM calls (replaces the script's run budget).")
        private Long maxCalls;

        @Option(
                names = "--max-cost",
                description = "Cap the run's cost, e.g. 0.50 (needs --prices).")
        private String maxCost;

        @Option(
                names = "--prices",
                description = "Price table: lines of 'model = input / output' per million tokens.")
        private File prices;

        @Option(
                names = "--journal",
                description =
                        "Run directory: makes the run durable, so it can pause on limits and resume later.")
        private File journal;

        @Option(
                names = "--store",
                description = "Trigger store for resumes (default: <journal>/../.loom-triggers).")
        private File store;

        @Option(
                names = "--wait",
                description =
                        "When the run pauses, stay alive and resume it when its limit resets.")
        private boolean waitForResume;

        @Option(
                names = "--lenient",
                description = "Treat features that aren't supported yet as warnings, not errors.")
        private boolean lenient;

        @Option(
                names = "--stop-at",
                description =
                        "Stop cleanly once this step (or checkpoint) has completed; resume the run to carry on.")
        private String stopAt;

        @Option(
                names = "--max-rewinds",
                description = "The most times the run may go back in all (default 20).")
        private Integer maxRewinds;

        @Option(
                names = "--ask-via",
                paramLabel = "telegram|command|console",
                description =
                        "Where to ask a person (default: the store's channel.json, else the console).")
        private String askVia;

        @Option(
                names = "--trace",
                arity = "0..1",
                fallbackValue = "text",
                paramLabel = "text|json",
                description =
                        "Show what agents think and do, live, on stderr (--trace=json for JSON lines).")
        private String trace;

        @CommandLine.Mixin private SteerMixin steerOpts = new SteerMixin();

        @CommandLine.Mixin private ObserveMixin observeOpts = new ObserveMixin();

        @CommandLine.Mixin private SecretOptions secrets = new SecretOptions();

        @CommandLine.Mixin private EnvFileOptions envFile = new EnvFileOptions();

        @CommandLine.Mixin private PromptOptions promptOptions = new PromptOptions();

        @CommandLine.Mixin private ClassesOptions classesOptions = new ClassesOptions();

        @CommandLine.Mixin private ContextOptions contextOptions = new ContextOptions();

        @CommandLine.Mixin private InvokerOptions invokerOptions = new InvokerOptions();

        @Option(
                names = "--exit-degraded",
                description =
                        "Exit with code 7 when the run finished but some step used its on_failure fallback (default: 0).")
        private boolean exitDegraded;

        @Option(
                names = {"-q", "--quiet"},
                description = "Do not print the workflow's returned value after the run.")
        private boolean quiet;

        @Option(
                names = "--review-notice",
                paramLabel = "high|medium|low|off",
                description =
                        "The lowest severity of open review findings mentioned in one line on standard error before the run (default: review.properties, else low). Never blocks the run.")
        private String reviewNotice;

        @Override
        public Integer call() throws Exception {
            if (!scriptFile.exists()) {
                System.err.println("Error: Script file not found: " + scriptFile);
                return 1;
            }
            if (!steerOpts.start(System.out, System.err)) return 2;
            WeaveEnv env = envFile.apply(WeaveEnv.system().withAskVia(askVia), scriptFile.toPath());
            if (env == null
                    || !classesOptions.apply(scriptFile.toPath(), env)
                    || !contextOptions.apply(scriptFile.toPath(), env)) return 2;
            env = secrets.apply(env, Prompts.console());
            if (env == null) return 2;
            env = promptOptions.apply(env);
            if (env == null) return 2;
            env = invokerOptions.apply(env);
            if (env == null) return 2;
            ObserveMixin.Opened observed = observeOpts.open(env);
            if (observed.failed()) return 2;
            env = observed.env();
            ReviewNotice.start(scriptFile, env, reviewNotice);
            try {
                return run(
                        scriptFile,
                        lootFile,
                        workflowName,
                        inputs,
                        maxTokens,
                        maxCalls,
                        maxCost,
                        prices,
                        journal == null ? null : journal.toPath(),
                        store == null ? null : store.toPath(),
                        waitForResume,
                        lenient,
                        trace,
                        stopAt,
                        maxRewinds,
                        exitDegraded,
                        quiet,
                        env);
            } finally {
                observed.finish();
            }
        }

        /** Keeps the page up for {@code linger} after the run, then stops serving. */
        static void lingerThenClose(
                io.github.llm4j.loom.observe.ObserveProvider.Session session,
                java.time.Duration linger,
                WeaveEnv env) {
            try {
                if (!linger.isZero()) {
                    env.out()
                            .println(
                                    "🔭 Keeping "
                                            + session.describe()
                                            + " up for "
                                            + io.github.llm4j.loom.execution.HarnessExecutor.human(
                                                    linger)
                                            + " (Ctrl-C to stop).");
                    env.sleeper().sleep(linger);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                session.close();
            }
        }
    }

    /**
     * Runs a workflow the way {@code weave run} does, with an explicit client factory and output
     * streams (so it can be tested). Exit codes: 0 done, 1 failed, 2 bad budget options, 3 stopped
     * by a budget, 4 paused.
     */
    static int execute(
            File scriptFile,
            File lootFile,
            String workflowName,
            Map<String, String> inputs,
            LLMClientFactory clientFactory,
            Long maxTokens,
            Long maxCalls,
            String maxCost,
            File pricesFile,
            io.github.llm4j.loom.runtime.HumanInterface human,
            java.io.PrintStream out,
            java.io.PrintStream err)
            throws Exception {
        WeaveEnv env =
                new WeaveEnv(
                        clientFactory,
                        human,
                        out,
                        err,
                        java.time.Clock.systemDefaultZone(),
                        io.github.llm4j.ratelimit.Sleeper.SYSTEM,
                        io.github.llm4j.loom.trigger.system.CommandRunner.SYSTEM,
                        WeaveEnv.selfCommand());
        return run(
                scriptFile,
                lootFile,
                workflowName,
                inputs,
                maxTokens,
                maxCalls,
                maxCost,
                pricesFile,
                null,
                null,
                false,
                env);
    }

    static int run(
            File scriptFile,
            File lootFile,
            String workflowName,
            Map<String, String> inputs,
            Long maxTokens,
            Long maxCalls,
            String maxCost,
            File pricesFile,
            Path journal,
            Path store,
            boolean wait,
            WeaveEnv env) {
        return run(
                scriptFile,
                lootFile,
                workflowName,
                inputs,
                maxTokens,
                maxCalls,
                maxCost,
                pricesFile,
                journal,
                store,
                wait,
                false,
                env);
    }

    static int run(
            File scriptFile,
            File lootFile,
            String workflowName,
            Map<String, String> inputs,
            Long maxTokens,
            Long maxCalls,
            String maxCost,
            File pricesFile,
            Path journal,
            Path store,
            boolean wait,
            boolean lenient,
            WeaveEnv env) {
        return run(
                scriptFile,
                lootFile,
                workflowName,
                inputs,
                maxTokens,
                maxCalls,
                maxCost,
                pricesFile,
                journal,
                store,
                wait,
                lenient,
                null,
                env);
    }

    static int run(
            File scriptFile,
            File lootFile,
            String workflowName,
            Map<String, String> inputs,
            Long maxTokens,
            Long maxCalls,
            String maxCost,
            File pricesFile,
            Path journal,
            Path store,
            boolean wait,
            boolean lenient,
            String trace,
            WeaveEnv env) {
        return run(
                scriptFile,
                lootFile,
                workflowName,
                inputs,
                maxTokens,
                maxCalls,
                maxCost,
                pricesFile,
                journal,
                store,
                wait,
                lenient,
                trace,
                null,
                env);
    }

    static int run(
            File scriptFile,
            File lootFile,
            String workflowName,
            Map<String, String> inputs,
            Long maxTokens,
            Long maxCalls,
            String maxCost,
            File pricesFile,
            Path journal,
            Path store,
            boolean wait,
            boolean lenient,
            String trace,
            String stopAt,
            WeaveEnv env) {
        return run(
                scriptFile,
                lootFile,
                workflowName,
                inputs,
                maxTokens,
                maxCalls,
                maxCost,
                pricesFile,
                journal,
                store,
                wait,
                lenient,
                trace,
                stopAt,
                null,
                env);
    }

    static int run(
            File scriptFile,
            File lootFile,
            String workflowName,
            Map<String, String> inputs,
            Long maxTokens,
            Long maxCalls,
            String maxCost,
            File pricesFile,
            Path journal,
            Path store,
            boolean wait,
            boolean lenient,
            String trace,
            String stopAt,
            Integer maxRewinds,
            WeaveEnv env) {
        return run(
                scriptFile,
                lootFile,
                workflowName,
                inputs,
                maxTokens,
                maxCalls,
                maxCost,
                pricesFile,
                journal,
                store,
                wait,
                lenient,
                trace,
                stopAt,
                maxRewinds,
                false,
                true,
                env);
    }

    /** The exit code a degraded run (finished, but with fallbacks) ends with when asked for. */
    static final int EXIT_DEGRADED = 7;

    static int run(
            File scriptFile,
            File lootFile,
            String workflowName,
            Map<String, String> inputs,
            Long maxTokens,
            Long maxCalls,
            String maxCost,
            File pricesFile,
            Path journal,
            Path store,
            boolean wait,
            boolean lenient,
            String trace,
            String stopAt,
            Integer maxRewinds,
            boolean exitDegraded,
            boolean quiet,
            WeaveEnv env) {
        if (trace != null && !trace.equals("text") && !trace.equals("json")) {
            env.err().println("Error: --trace takes text or json, got " + trace);
            return 2;
        }
        Path runDir = journal == null ? null : journal.toAbsolutePath().normalize();
        Path storeDir =
                runDir == null
                        ? null
                        : (store != null
                                ? store.toAbsolutePath().normalize()
                                : Runs.defaultStore(runDir));
        RunSpec spec =
                new RunSpec(
                                scriptFile.getAbsolutePath(),
                                lootFile == null ? null : lootFile.getAbsolutePath(),
                                workflowName,
                                inputs,
                                maxTokens,
                                maxCalls,
                                maxCost,
                                pricesFile == null ? null : pricesFile.getAbsolutePath(),
                                storeDir == null ? null : storeDir.toString(),
                                lenient,
                                trace)
                        .withPrompts(env.prompts());
        if (runDir != null) spec.write(runDir);
        Runs.Result result =
                Runs.execute(spec.withStopAt(stopAt).withMaxRewinds(maxRewinds), runDir, null, env);
        if (result.exit() == 4 && wait && runDir != null && result.resumeAt() != null) {
            return waitForRun(runDir, storeDir, env);
        }
        if (result.exit() == 0 && !quiet) Runs.printResult(result.output(), env);
        return result.exit() == 0 && result.degraded() && exitDegraded
                ? EXIT_DEGRADED
                : result.exit();
    }

    /**
     * {@code --wait}: sleeps until the run's resume trigger is due, resumes it, and repeats until
     * it ends.
     */
    static int waitForRun(Path runDir, Path storeDir, WeaveEnv env) {
        io.github.llm4j.loom.trigger.FileTriggerStore store =
                new io.github.llm4j.loom.trigger.FileTriggerStore(storeDir);
        String id = Runs.resumeTriggerId(runDir);
        io.github.llm4j.loom.trigger.TriggerTarget.Outcome[] last =
                new io.github.llm4j.loom.trigger.TriggerTarget.Outcome[1];
        io.github.llm4j.loom.trigger.TriggerRunner runner =
                new io.github.llm4j.loom.trigger.TriggerRunner(
                                store, new WeaveTriggerTarget(storeDir, env), env.clock())
                        .onFired(
                                f -> {
                                    if (f.trigger().id().equals(id)) last[0] = f.outcome();
                                });
        for (int round = 0; round < 10_000; round++) {
            var pending = store.get(id);
            if (pending.isEmpty()) break;
            java.time.Duration wait =
                    java.time.Duration.between(env.clock().instant(), pending.get().nextFire());
            if (!wait.isNegative() && !wait.isZero()) {
                env.out().println("⏳ Waiting " + HarnessExecutor.human(wait) + " to resume...");
                try {
                    env.sleeper().sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 4;
                }
            }
            runner.tick();
        }
        if (last[0] == null) return 4;
        return switch (last[0].status()) {
            case DONE -> 0;
            case HUMAN, SUSPENDED -> 4;
            default -> 1;
        };
    }

    @Command(
            name = "check",
            description =
                    "Checks a script — tools, knowledge, secrets, names — without running it.")
    static class CheckCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The .loom script to check.")
        private File scriptFile;

        @Option(
                names = {"-l", "--loot"},
                description = "The .loot tool mapping file.")
        private File lootFile;

        @Option(
                names = "--lenient",
                description = "Treat features that aren't supported yet as warnings.")
        private boolean lenient;

        @Option(
                names = "--format",
                description =
                        "text (default) or json: one JSON object with the problems in it, for tools (the editor reads this).",
                defaultValue = "text")
        private String format;

        @Option(
                names = "--no-env",
                description =
                        "Check the script without keys: names that are not set in the environment are listed as 'not set yet' instead of being problems.")
        private boolean noEnv;

        @Option(
                names = "--strict",
                description =
                        "Treat warnings as errors (exit 2), so a build can require a clean script.")
        private boolean strict;

        @Option(
                names = "--substitution",
                paramLabel = "braces",
                description =
                        "Preview what would change under `substitution: braces`: every string where a variable's bare name is replaced today. Writes nothing.")
        private String substitution;

        @Option(
                names = "--fix-substitution",
                description =
                        "Rewrite the bare words that were meant as variables to {name}, after showing the changed lines and asking. A word inside another word is left for you.")
        private boolean fixSubstitution;

        @Option(
                names = "--yes",
                description = "With --fix-substitution: do not ask, write the rewrite.")
        private boolean yes;

        @CommandLine.Mixin private SecretOptions secrets = new SecretOptions();

        @CommandLine.Mixin private EnvFileOptions envFile = new EnvFileOptions();

        @CommandLine.Mixin private PromptOptions promptOptions = new PromptOptions();

        @CommandLine.Mixin private ClassesOptions classesOptions = new ClassesOptions();

        @CommandLine.Mixin private ContextOptions contextOptions = new ContextOptions();

        @Override
        public Integer call() {
            if (substitution != null || fixSubstitution)
                return SubstitutionCheck.run(
                        scriptFile, substitution, fixSubstitution, yes, WeaveEnv.system());
            WeaveEnv env = envFile.apply(WeaveEnv.system(), scriptFile.toPath());
            if (env == null
                    || !classesOptions.apply(scriptFile.toPath(), env)
                    || !contextOptions.apply(scriptFile.toPath(), env)) return 2;
            env = secrets.apply(env, Prompts.console());
            if (env == null) return 2;
            env = promptOptions.apply(env);
            if (env == null) return 2;
            if (!format.equals("text") && !format.equals("json")) {
                System.err.println("Error: --format takes text or json.");
                return 2;
            }
            return check(
                    scriptFile,
                    lootFile,
                    new CheckSettings(lenient, format.equals("json"), noEnv, strict),
                    env);
        }
    }

    /** What {@code weave check} was asked to do besides check. */
    record CheckSettings(boolean lenient, boolean json, boolean noEnv, boolean strict) {
        static final CheckSettings DEFAULT = new CheckSettings(false, false, false, false);
    }

    /**
     * Runs every load-time check without calling a model, embedding or starting a server. Prints
     * every problem with its line; exit 0 when there are no errors, 2 otherwise. Never prints
     * secret values.
     */
    static int check(File scriptFile, File lootFile, boolean lenient, WeaveEnv env) {
        return check(scriptFile, lootFile, new CheckSettings(lenient, false, false, false), env);
    }

    private static final java.util.regex.Pattern NEEDS_KEY =
            java.util.regex.Pattern.compile("needs ([A-Za-z0-9_]+) in the environment");
    private static final java.util.regex.Pattern LINE_OF =
            java.util.regex.Pattern.compile("line (\\d+)");

    static int check(File scriptFile, File lootFile, CheckSettings settings, WeaveEnv env) {
        io.github.llm4j.loom.ast.LoomScript script;
        if (!scriptFile.isFile()) {
            String why =
                    "there is no file at "
                            + scriptFile.getAbsolutePath()
                            + ". Check the path (weave next, run in the project folder, says where the script is)";
            if (settings.json())
                env.out()
                        .println(
                                checkJson(
                                        scriptFile,
                                        List.of(new JsonProblem("error", 0, why)),
                                        List.of()));
            else env.out().println("✗ " + scriptFile.getName() + ": " + why);
            return 2;
        }
        try {
            script = new LoomLoader().load(scriptFile.getAbsolutePath());
        } catch (Exception e) {
            if (settings.json()) {
                var m = LINE_OF.matcher(String.valueOf(e.getMessage()));
                int line = m.find() ? Integer.parseInt(m.group(1)) : 0;
                env.out()
                        .println(
                                checkJson(
                                        scriptFile,
                                        List.of(
                                                new JsonProblem(
                                                        "error",
                                                        line,
                                                        String.valueOf(e.getMessage()))),
                                        List.of()));
            } else {
                env.out().println("✗ " + scriptFile.getName() + ": " + e.getMessage());
            }
            return 2;
        }
        Validation validation = validate(scriptFile, lootFile, settings, env, script);
        java.util.List<String> keyWarnings = validation.keyWarnings();
        java.util.List<String> reachProblems = validation.reachProblems();
        java.util.Set<String> notSetYet = validation.notSetYet();
        List<io.github.llm4j.loom.execution.ScriptValidator.Problem> problems =
                validation.problems();
        var error = io.github.llm4j.loom.execution.ScriptValidator.Severity.ERROR;
        long errors =
                problems.stream().filter(p -> p.severity() == error || settings.strict()).count()
                        + reachProblems.size()
                        + (settings.strict() ? keyWarnings.size() : 0);
        if (settings.json()) {
            List<JsonProblem> out = new ArrayList<>();
            for (String r : reachProblems) out.add(new JsonProblem("error", 0, r));
            for (String w : keyWarnings)
                out.add(new JsonProblem(settings.strict() ? "error" : "warning", 0, w));
            for (var p : problems)
                out.add(
                        new JsonProblem(
                                p.severity() == error || settings.strict() ? "error" : "warning",
                                p.line(),
                                (p.construct() == null ? "" : p.construct() + ": ") + p.message()));
            env.out().println(checkJson(scriptFile, out, new ArrayList<>(notSetYet)));
            return errors == 0 ? 0 : 2;
        }
        for (String r : reachProblems) env.out().println("✗ " + r);
        for (String w : keyWarnings) env.out().println((settings.strict() ? "✗ " : "⚠ ") + w);
        for (var p : problems)
            env.out().println((p.severity() == error || settings.strict() ? "✗ " : "⚠ ") + p);
        if (!notSetYet.isEmpty())
            env.out().println("ℹ not set yet (needed to run): " + String.join(", ", notSetYet));
        java.util.SortedSet<String> invokerNames =
                io.github.llm4j.loom.execution.InvokerRefs.names(script);
        if (!invokerNames.isEmpty()) {
            env.out()
                    .println(
                            "ℹ acts as the person who runs it, with their "
                                    + String.join(", ", invokerNames)
                                    + ": the application supplies these when it runs the workflow (weave run --invoker-env NAME=VARIABLE does it from a terminal)");
        }
        java.util.Set<String> codeSteps = new java.util.TreeSet<>();
        for (var workflow : script.getWorkflows()) {
            io.github.llm4j.loom.ast.StatementWalker.walk(
                    workflow.getStatements(),
                    st -> {
                        if (st instanceof io.github.llm4j.loom.ast.RunStmt r)
                            codeSteps.add(r.getTaskName());
                    });
        }
        if (!codeSteps.isEmpty()) {
            env.out()
                    .println(
                            "ℹ the code inside "
                                    + String.join(", ", codeSteps)
                                    + " is not read by weave check or weave audit: only its own Java tests check it (weave guide 6)");
        }
        if (errors == 0) {
            env.out()
                    .println(
                            "✓ "
                                    + scriptFile.getName()
                                    + ": ready to run"
                                    + (problems.size() + keyWarnings.size() == 0
                                            ? ""
                                            : " ("
                                                    + (problems.size() + keyWarnings.size())
                                                    + " warning"
                                                    + (problems.size() + keyWarnings.size() == 1
                                                            ? ""
                                                            : "s")
                                                    + ")"));
            env.out()
                    .println(
                            "  next: weave next says what to do now; weave guide recipes has tested changes to copy");
            return 0;
        }
        boolean keys =
                problems.stream()
                        .anyMatch(
                                p ->
                                        p.message().contains("is not set")
                                                || p.message().contains("in the environment"));
        if (keys && !settings.noEnv())
            env.out()
                    .println(
                            "Some of these are names that are not set yet. Set them, or use --no-env to check the script without keys.");
        env.out()
                .println(
                        errors
                                + " problem"
                                + (errors == 1 ? "" : "s")
                                + " in "
                                + scriptFile.getName());
        return 2;
    }

    /** What validating a script found, before anything is printed. */
    record Validation(
            List<io.github.llm4j.loom.execution.ScriptValidator.Problem> problems,
            java.util.List<String> keyWarnings,
            java.util.List<String> reachProblems,
            java.util.Set<String> notSetYet) {}

    /**
     * Everything {@code weave check} verifies about a loaded script, without printing: the
     * validator's problems, key warnings, tool-reach problems and the names not set yet. Calls no
     * model and starts nothing. {@code weave review} reads the same findings through this.
     */
    static Validation validate(
            File scriptFile,
            File lootFile,
            CheckSettings settings,
            WeaveEnv env,
            io.github.llm4j.loom.ast.LoomScript script) {
        io.github.llm4j.loom.context.ContextFiles.beside(scriptFile.toPath());
        ToolRegistry registry = new ToolRegistry();
        if (lootFile != null && lootFile.exists())
            new LootLoader().loadIntoRegistry(lootFile.getAbsolutePath(), registry);
        java.util.List<String> keyWarnings = new java.util.ArrayList<>();
        java.util.List<EnvExampleCheck.Finding> inTemplates =
                EnvExampleCheck.findings(scriptFile.toPath());
        if (!inTemplates.isEmpty()) keyWarnings.add(EnvExampleCheck.warning(inTemplates));
        java.util.List<String> reachProblems =
                lootFile != null && lootFile.exists()
                        ? LootLoader.reachProblems(lootFile.toPath())
                        : java.util.List.of();
        LLMClientFactory models = env.models();
        java.util.Set<String> notSetYet = new java.util.TreeSet<>();
        HarnessExecutor executor =
                new HarnessExecutor(
                        script,
                        registry,
                        new LLMClientFactory() {
                            @Override
                            public io.github.llm4j.LLMClient createClient(String model) {
                                throw new IllegalStateException(
                                        "weave check never creates model clients");
                            }

                            @Override
                            public String problem(String model) {
                                String problem =
                                        models.problem(
                                                model); // names and keys are checked; nothing is
                                // contacted
                                if (problem != null && settings.noEnv()) {
                                    var m = NEEDS_KEY.matcher(problem);
                                    if (m.find()) {
                                        notSetYet.add(m.group(1));
                                        return null;
                                    }
                                }
                                return problem;
                            }
                        });
        executor.setLenient(settings.lenient());
        if (lootFile != null && lootFile.exists())
            executor.setToolReach(LootLoader.reaches(lootFile.toPath()));
        executor.setBaseDir(scriptFile.getAbsoluteFile().getParentFile().toPath());
        java.util.function.Function<String, String> lookup = env.env();
        executor.setEnvLookup(
                settings.noEnv()
                        ? name -> {
                            String value = lookup.apply(name);
                            if (value == null) {
                                notSetYet.add(name);
                                return "not-set-yet";
                            }
                            return value;
                        }
                        : lookup);
        executor.setSecretStore(env.secrets());
        executor.setPromptCatalog(
                io.github.llm4j.loom.prompt.PromptSupport.catalog(
                        script, scriptFile.toPath(), env.prompts()));
        executor.setHumanInterface(env.human()); // the CLI always has a console
        List<io.github.llm4j.loom.execution.ScriptValidator.Problem> problems =
                new io.github.llm4j.loom.execution.ScriptValidator()
                        .validate(script, executor.validationContext());
        // With --no-env a name that is not set stands in as "not-set-yet", which is not an address
        // or a number: what the stand-in breaks is already said as "not set yet".
        if (settings.noEnv()) problems = withoutStandInFailures(script, problems, notSetYet);
        return new Validation(problems, keyWarnings, reachProblems, notSetYet);
    }

    /**
     * With {@code --no-env} a name that is not set stands in as {@code not-set-yet}. What that
     * breaks is already said as "not set yet": a message naming the stand-in, and a tool whose
     * option is read from such a name and so cannot be built (a webhook whose URL is the stand-in).
     */
    private static List<io.github.llm4j.loom.execution.ScriptValidator.Problem>
            withoutStandInFailures(
                    io.github.llm4j.loom.ast.LoomScript script,
                    List<io.github.llm4j.loom.execution.ScriptValidator.Problem> problems,
                    java.util.Set<String> notSetYet) {
        java.util.Set<String> standIns = new java.util.HashSet<>();
        for (var tool : script.getTools())
            for (var option : tool.getOptions().values())
                if (option.fromEnv() && notSetYet.contains(option.value()))
                    standIns.add(tool.getName());
        return problems.stream()
                .filter(p -> !p.message().contains("not-set-yet"))
                .filter(
                        p ->
                                p.construct() == null
                                        || !p.construct().startsWith("tool ")
                                        || !standIns.contains(p.construct().substring(5))
                                        // a misspelt option is the script's own mistake
                                        || p.message().contains("unknown option")
                                        || p.message().contains("requires"))
                .toList();
    }

    /** One problem as the JSON of {@code weave check --format json} carries it. */
    private record JsonProblem(String severity, int line, String message) {}

    /**
     * The JSON object: version 1, whether the script is fine, the problems, and the names not set
     * yet. Printed with exit 0 or 2, like the text form.
     */
    private static String checkJson(
            File scriptFile, List<JsonProblem> problems, List<String> notSetYet) {
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("version", 1);
        root.put("file", scriptFile.getAbsolutePath());
        root.put("ok", problems.stream().noneMatch(p -> p.severity().equals("error")));
        List<java.util.Map<String, Object>> list = new ArrayList<>();
        for (JsonProblem p : problems) {
            java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
            d.put("severity", p.severity());
            d.put("file", scriptFile.getAbsolutePath());
            d.put("line", p.line());
            d.put("message", p.message());
            list.add(d);
        }
        root.put("diagnostics", list);
        root.put("notSetYet", notSetYet);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Command(name = "resume", description = "Resumes a paused run now, from its run directory.")
    static class ResumeCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The run directory (as given to run --journal).")
        private File runDir;

        @Option(
                names = "--stop-at",
                description = "Stop cleanly once this step (or checkpoint) has completed.")
        private String stopAt;

        @Option(
                names = "--ask-via",
                paramLabel = "telegram|command|console",
                description =
                        "Where to ask a person (default: the store's channel.json, else the console).")
        private String askVia;

        @CommandLine.Mixin private SecretOptions secrets = new SecretOptions();

        @CommandLine.Mixin private InvokerOptions invokerOptions = new InvokerOptions();

        @CommandLine.Mixin private ObserveMixin observeOpts = new ObserveMixin();

        @Override
        public Integer call() {
            WeaveEnv env = secrets.apply(WeaveEnv.system().withAskVia(askVia), Prompts.console());
            if (env == null) return 2;
            env = invokerOptions.apply(env);
            if (env == null) return 2;
            ObserveMixin.Opened observed = observeOpts.open(env);
            if (observed.failed()) return 2;
            try {
                return resume(runDir.toPath(), stopAt, observed.env());
            } finally {
                observed.finish();
            }
        }
    }

    static int resume(Path runDir, WeaveEnv env) {
        return resume(runDir, null, env);
    }

    static int resume(Path runDir, String stopAt, WeaveEnv env) {
        Path dir = runDir.toAbsolutePath().normalize();
        RunSpec spec;
        try {
            spec = RunSpec.read(dir);
        } catch (RuntimeException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        Runs.Result r = Runs.execute(spec.withStopAt(stopAt), dir, null, env);
        if (r.exit() != 4 && spec.store() != null) {
            new io.github.llm4j.loom.trigger.FileTriggerStore(Path.of(spec.store()))
                    .remove(Runs.resumeTriggerId(dir));
        }
        return r.exit();
    }

    @Command(
            name = "tick",
            description =
                    "Fires every trigger that is due in a store, then exits (for cron, systemd, launchd).")
    static class TickCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The trigger store directory.")
        private File store;

        @Option(
                names = "--ask-via",
                paramLabel = "telegram|command|console",
                description =
                        "Where to ask a person (default: the store's channel.json, else the console).")
        private String askVia;

        @CommandLine.Mixin private SecretOptions secrets = new SecretOptions();

        @CommandLine.Mixin private ObserveMixin observeOpts = new ObserveMixin();

        @Override
        public Integer call() {
            WeaveEnv env = secrets.apply(WeaveEnv.system().withAskVia(askVia), Prompts.console());
            if (env == null) return 2;
            ObserveMixin.Opened observed = observeOpts.open(env);
            if (observed.failed()) return 2;
            try {
                return tick(store.toPath(), observed.env());
            } finally {
                observed.finish();
            }
        }
    }

    static int tick(Path storeDir, WeaveEnv env) {
        Path dir = storeDir.toAbsolutePath().normalize();
        io.github.llm4j.loom.trigger.FileTriggerStore store =
                new io.github.llm4j.loom.trigger.FileTriggerStore(dir);
        java.util.Optional<io.github.llm4j.loom.channel.Channels.Runtime> channel;
        try {
            channel =
                    io.github.llm4j.loom.channel.Channels.open(
                            dir, env.askVia(), env.env(), env.clock());
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        if (channel.isPresent()) {
            // answers that arrived since the last tick are recorded first (each leaves a resume
            // trigger), then what is unsent, due a reminder or expired is dealt with
            try {
                var summary = channel.get().listener().pollOnce(java.time.Duration.ZERO);
                if (summary.recorded() > 0)
                    env.out().println("💬 Recorded " + summary.recorded() + " answer(s)");
                channel.get().listener().maintain();
            } catch (java.io.IOException | RuntimeException e) {
                env.err()
                        .println(
                                "⚠ The channel could not be read this time ("
                                        + e.getMessage()
                                        + "); questions already sent are still waiting.");
            }
        }
        int fired =
                new io.github.llm4j.loom.trigger.TriggerRunner(
                                store, new WeaveTriggerTarget(dir, env), env.clock())
                        .tick();
        if (fired > 0) env.out().println("🔔 Fired " + fired + " trigger(s)");
        Triggers.resyncExact(dir, store, env);
        return 0;
    }

    @Command(
            name = "daemon",
            description = "Keeps running and fires triggers from a store as they come due.")
    static class DaemonCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The trigger store directory.")
        private File store;

        @Option(
                names = "--poll",
                description = "How often to look for due triggers, e.g. 5s.",
                defaultValue = "5s")
        private String poll;

        @Option(
                names = "--ask-via",
                paramLabel = "telegram|command|console",
                description =
                        "Where to ask a person (default: the store's channel.json, else the console).")
        private String askVia;

        @CommandLine.Mixin private SecretOptions secrets = new SecretOptions();

        @CommandLine.Mixin private ObserveMixin observeOpts = new ObserveMixin();

        @Override
        public Integer call() throws Exception {
            WeaveEnv env = secrets.apply(WeaveEnv.system().withAskVia(askVia), Prompts.console());
            if (env == null) return 2;
            ObserveMixin.Opened observed = observeOpts.open(env);
            if (observed.failed()) return 2;
            observed.closeAtExit(); // a daemon is stopped with Ctrl-C: the session closes then,
            // flushing the outbox
            return daemon(
                    store.toPath(),
                    io.github.llm4j.loom.trigger.Schedules.parse(poll),
                    null,
                    observed.env());
        }
    }

    static int daemon(
            Path storeDir, java.time.Duration poll, java.time.Duration runFor, WeaveEnv env)
            throws InterruptedException {
        Path dir = storeDir.toAbsolutePath().normalize();
        io.github.llm4j.loom.trigger.TriggerRunner runner =
                new io.github.llm4j.loom.trigger.TriggerRunner(
                        new io.github.llm4j.loom.trigger.FileTriggerStore(dir),
                        new WeaveTriggerTarget(dir, env),
                        env.clock());
        java.util.Optional<io.github.llm4j.loom.channel.Channels.Runtime> channel;
        try {
            channel =
                    io.github.llm4j.loom.channel.Channels.open(
                            dir, env.askVia(), env.env(), env.clock());
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        env.out()
                .println(
                        "👂 Watching "
                                + dir
                                + " (every "
                                + HarnessExecutor.human(poll)
                                + ")"
                                + (channel.isPresent()
                                        ? " and listening on " + channel.get().channel().name()
                                        : "")
                                + ". Ctrl+C to stop.");
        java.util.concurrent.atomic.AtomicBoolean stopListening =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread listener = null;
        if (channel.isPresent()) {
            var rt = channel.get();
            listener =
                    new Thread(
                            () ->
                                    rt.listen(
                                            java.time.Duration.ofSeconds(runFor == null ? 30 : 1),
                                            stopListening,
                                            last ->
                                                    last.isZero()
                                                            ? java.time.Duration.ofSeconds(5)
                                                            : last.multipliedBy(2)
                                                                                    .compareTo(
                                                                                            java
                                                                                                    .time
                                                                                                    .Duration
                                                                                                    .ofMinutes(
                                                                                                            5))
                                                                            > 0
                                                                    ? java.time.Duration.ofMinutes(
                                                                            5)
                                                                    : last.multipliedBy(2)),
                            "loom-channel");
            listener.setDaemon(true);
            listener.start();
        }
        runner.start(poll);
        try {
            if (runFor == null) Thread.currentThread().join();
            else Thread.sleep(runFor.toMillis());
        } finally {
            stopListening.set(true);
            if (listener != null) listener.interrupt();
            runner.stop();
        }
        return 0;
    }

    @Command(
            name = "observe",
            description =
                    "Looks after what observing leaves on disk: observe flush delivers an outbox, observe purge removes one.",
            subcommands = {
                ObserveFlushCommand.class,
                ObservePurgeCommand.class,
                ObserveRulesCommand.class,
                ObserveHashPasswordCommand.class
            })
    static class ObserveCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            CommandLine.usage(this, System.out);
            return 0;
        }
    }

    @Command(
            name = "flush",
            description =
                    "Delivers what an earlier process left in its local outbox to the Fabric server (the server must know this name's key).")
    static class ObserveFlushCommand implements Callable<Integer> {
        @CommandLine.Mixin private ObserveMixin observeOpts = new ObserveMixin();

        @CommandLine.Mixin private SecretOptions secrets = new SecretOptions();

        @Override
        public Integer call() {
            WeaveEnv env = secrets.apply(WeaveEnv.system(), Prompts.console());
            if (env == null) return 2;
            try {
                io.github.llm4j.loom.observe.ObserveOptions options = observeOpts.parsed();
                if (options == null || options.fabric() == null) {
                    System.err.println(
                            "Error: observe flush needs --observe-fabric (or LOOM_FABRIC_URL) and --observe-name.");
                    return 2;
                }
                long left =
                        io.github.llm4j.loom.observe.Observe.provider()
                                .flush(options, env.secrets(), env.out());
                return left == 0 ? 0 : 1;
            } catch (IllegalArgumentException
                    | io.github.llm4j.loom.observe.Observe.Unavailable e) {
                System.err.println("Error: " + e.getMessage());
                return 2;
            }
        }
    }

    @Command(
            name = "rules",
            description =
                    "Checks a rules file (what counts as trouble for each agent) against a script, without running anything. Default file: <script>.inspect beside the script.")
    static class ObserveRulesCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The .loom script the rules are for.")
        private File script;

        @Option(
                names = "--observe-rules",
                paramLabel = "file",
                description = "The rules file (default: <script>.inspect).")
        private File rules;

        @Option(
                names = "--init",
                description =
                        "Write a commented starter rules file beside the script (never over an existing one). Everything in it is a comment, so nothing changes until you uncomment a line.")
        private boolean init;

        /** What a new rules file holds: all comments, so the defaults still apply. */
        private static final String STARTER =
                "# Rules for this workflow: what counts as trouble, per agent. Everything is optional;\n"
                        + "# the defaults are sensible. Uncomment a line and change it to make an agent more or less\n"
                        + "# sensitive. Check the file with: weave observe rules <script>\n"
                        + "#\n"
                        + "# Researcher { context_nearly_full: warn at 80%, high at 92% }\n"
                        + "# Editor     { same_action: 3 times }\n"
                        + "# *          { paused_longer_than: 90s\n"
                        + "#              compaction_thrash: 4 in 10 calls\n"
                        + "#              tool_success_rate: below 70% over 10 calls\n"
                        + "#              access_denied: 3 in 60s }\n";

        @Override
        public Integer call() {
            if (!script.exists()) {
                System.err.println("Error: Script file not found: " + script);
                return 2;
            }
            File file =
                    rules != null
                            ? rules
                            : new File(
                                    script.getParentFile() == null
                                            ? new File(".")
                                            : script.getParentFile(),
                                    script.getName().replaceFirst("\\.[^.]*$", "") + ".inspect");
            if (init) return writeStarter(file);
            if (!hasRules(file)) {
                System.out.println(
                        file
                                + (file.exists() ? " has no rules" : " does not exist")
                                + ": the defaults apply. weave observe rules "
                                + script
                                + " --init writes a commented starter file.");
                return 0;
            }
            try {
                LoomScript loaded =
                        new io.github.llm4j.loom.execution.LoomLoader()
                                .load(script.getAbsolutePath());
                int errors =
                        io.github.llm4j.loom.observe.Observe.provider()
                                .checkRules(loaded, file.toPath(), System.out);
                return errors == 0 ? 0 : 1;
            } catch (io.github.llm4j.loom.observe.Observe.Unavailable
                    | java.io.IOException
                    | RuntimeException e) {
                System.err.println("Error: " + e.getMessage());
                return 2;
            }
        }

        private static int writeStarter(File file) {
            if (file.exists()) {
                System.err.println(
                        "Error: " + file + " already exists; --init never overwrites a file.");
                return 2;
            }
            try {
                java.nio.file.Files.writeString(file.toPath(), STARTER);
            } catch (java.io.IOException e) {
                System.err.println("Error: could not write " + file + ": " + e.getMessage());
                return 2;
            }
            System.out.println(
                    "Wrote "
                            + file
                            + " (all comments: the defaults still apply until you uncomment a line).");
            return 0;
        }

        /**
         * Whether the file holds a rule: anything left after blank lines and {@code #} / {@code //}
         * comments.
         */
        private static boolean hasRules(File file) {
            if (!file.isFile()) return false;
            try {
                return java.nio.file.Files.readAllLines(file.toPath()).stream()
                        .map(String::strip)
                        .anyMatch(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith("//"));
            } catch (java.io.IOException e) {
                return true; // let the add-on say what is wrong with it
            }
        }
    }

    @Command(
            name = "hash-password",
            description =
                    "Prints a salted hash of the admin password. Store that as LOOM_INSPECT_ADMIN_PASSWORD instead of the password (weave secrets set ... then paste it); signing in still takes the password. The password is read from the environment variable named by --password-env (default LOOM_INSPECT_NEW_PASSWORD), or asked for.")
    static class ObserveHashPasswordCommand implements Callable<Integer> {
        @Option(
                names = "--password-env",
                paramLabel = "VAR",
                description = "The environment variable holding the password.",
                defaultValue = "LOOM_INSPECT_NEW_PASSWORD")
        private String passwordEnv;

        @Override
        public Integer call() {
            String p = System.getenv(passwordEnv);
            if ((p == null || p.isEmpty()) && System.console() != null) {
                char[] typed = System.console().readPassword("Admin password: ");
                p = typed == null ? null : new String(typed);
            }
            if (p == null || p.length() < 12) {
                System.err.println(
                        "Error: give a password of at least 12 characters (in "
                                + passwordEnv
                                + ", or typed when asked).");
                return 2;
            }
            try {
                System.out.println(io.github.llm4j.loom.observe.Observe.provider().hashPassword(p));
                return 0;
            } catch (io.github.llm4j.loom.observe.Observe.Unavailable e) {
                System.err.println("Error: " + e.getMessage());
                return 2;
            }
        }
    }

    @Command(
            name = "purge",
            description =
                    "Deletes a process name's local outbox that no running process is using. Undelivered events in it are lost.")
    static class ObservePurgeCommand implements Callable<Integer> {
        @CommandLine.Mixin private ObserveMixin observeOpts = new ObserveMixin();

        @Override
        public Integer call() {
            if (observeOpts.name() == null || observeOpts.name().isBlank()) {
                System.err.println("Error: observe purge needs --observe-name.");
                return 2;
            }
            try {
                io.github.llm4j.loom.observe.Observe.provider()
                        .purge(observeOpts.name().trim(), observeOpts.outbox(), System.out);
                return 0;
            } catch (io.github.llm4j.loom.observe.Observe.Unavailable e) {
                System.err.println("Error: " + e.getMessage());
                return 2;
            }
        }
    }

    @Command(
            name = "triggers",
            description =
                    "Lists and manages stored triggers, and installs system triggers.%n"
                            + "  list | pause <id> | enable <id> | cancel <id> | fire <id> | install | uninstall")
    static class TriggersCommand implements Callable<Integer> {
        @Parameters(
                index = "0",
                description = "list, pause, enable, cancel, fire, install or uninstall.")
        private String action;

        @Parameters(index = "1", description = "The trigger store directory.")
        private File store;

        @Parameters(
                index = "2",
                arity = "0..1",
                description = "The trigger id (pause, enable, cancel, fire).")
        private String id;

        @Option(
                names = "--backend",
                description =
                        "cron, systemd, launchd, windows or cloud-scheduler (default: detected).")
        private String backend;

        @Option(
                names = "--mode",
                description = "heartbeat (default) or exact.",
                defaultValue = "heartbeat")
        private String mode;

        @Option(
                names = "--every",
                description = "Heartbeat interval, e.g. 5m.",
                defaultValue = "5m")
        private String every;

        @Option(
                names = "--apply",
                description = "Make the change (without it, only show what would change).")
        private boolean apply;

        @Option(
                names = "--weave",
                description = "The command that runs weave (default: this Java and classpath).")
        private String weave;

        @Option(
                names = "--env-file",
                description = "A file of KEY=VALUE lines (API keys) loaded before each tick.")
        private File envFile;

        @Option(
                names = "--url",
                description = "cloud-scheduler: the service URL exposing /loom/tick.")
        private String url;

        @Option(
                names = "--service-account",
                description = "cloud-scheduler: service account for the OIDC token.")
        private String serviceAccount;

        @Option(names = "--region", description = "cloud-scheduler: location, e.g. asia-south1.")
        private String region;

        @Override
        public Integer call() throws Exception {
            WeaveEnv env = WeaveEnv.system();
            if (weave != null) env = env.withWeave(List.of(weave.trim().split("\\s+")));
            Triggers.InstallOptions options =
                    new Triggers.InstallOptions(
                            backend,
                            mode,
                            every,
                            apply,
                            envFile == null ? null : envFile.toPath(),
                            url,
                            serviceAccount,
                            region,
                            Path.of(System.getProperty("user.home")),
                            System.getProperty("os.name"));
            return Triggers.run(action, store.toPath(), id, options, env);
        }
    }

    @Command(
            name = "schedule",
            description =
                    "schedule sync <script> --store <dir>: writes a script's schedule blocks to a trigger store.")
    static class ScheduleCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "sync")
        private String action;

        @Parameters(index = "1", description = "The .loom script.")
        private File script;

        @Option(names = "--store", required = true, description = "The trigger store directory.")
        private File store;

        @Override
        public Integer call() {
            if (!"sync".equals(action)) {
                System.err.println(
                        "Error: unknown action '"
                                + action
                                + "'. Use: weave schedule sync <script> --store <dir>");
                return 2;
            }
            return Triggers.syncSchedules(script.toPath(), store.toPath(), WeaveEnv.system());
        }
    }

    @Command(name = "package", description = "Packages a .loom workflow into a runnable JAR.")
    static class PackageCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The .loom script file.")
        private File scriptFile;

        @Option(
                names = {"-l", "--loot"},
                description = "The .loot tool mapping file.")
        private File lootFile;

        @Option(
                names = {"-o", "--out"},
                description = "Output JAR filename.",
                defaultValue = "loom-app.jar")
        private String outputName;

        @Option(
                names = {"--fat"},
                description = "Create a fat JAR containing all dependencies.")
        private boolean fatJar;

        @Option(
                names = {"--thin"},
                description = "Create a thin JAR (default).",
                defaultValue = "true")
        private boolean thinJar;

        @Option(
                names = "--review-notice",
                paramLabel = "high|medium|low|off",
                description =
                        "The lowest severity of open review findings mentioned in one line on standard error (default: review.properties, else low). Never blocks packaging.")
        private String reviewNotice;

        @Override
        public Integer call() throws Exception {
            if (!scriptFile.exists()) {
                System.err.println("Error: Script file not found: " + scriptFile);
                return 1;
            }
            ReviewNotice.start(scriptFile, WeaveEnv.system(), reviewNotice);

            File outFile = new File(outputName);
            System.out.println("📦 Packaging workflow into: " + outFile.getAbsolutePath());

            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes()
                    .put(Attributes.Name.MAIN_CLASS, PackagedLoomApp.class.getName());

            try (JarOutputStream jos =
                    new JarOutputStream(new FileOutputStream(outFile), manifest)) {
                // Add script and loot
                addFileToJar(jos, scriptFile, "app.loom");
                if (lootFile != null && lootFile.exists()) {
                    addFileToJar(jos, lootFile, "app.loot");
                }

                if (fatJar) {
                    System.out.println("🚀 Building Fat JAR (bundling dependencies)...");
                    bundleDependencies(jos);
                } else {
                    System.out.println("📄 Building Thin JAR (referencing dependencies)...");
                    // bundleCurrentProject adds all classes in the current classpath entry for this
                    // project
                    bundleCurrentProject(jos);
                }
            }

            System.out.println("✅ Packaging complete: " + outputName);
            return 0;
        }

        private void addFileToJar(JarOutputStream jos, File file, String entryName)
                throws IOException {
            jos.putNextEntry(new JarEntry(entryName));
            Files.copy(file.toPath(), jos);
            jos.closeEntry();
        }

        private void addClassToJar(JarOutputStream jos, Class<?> clazz) throws IOException {
            String entryName = clazz.getName().replace('.', '/') + ".class";
            try (InputStream is = clazz.getResourceAsStream("/" + entryName)) {
                if (is != null) {
                    jos.putNextEntry(new JarEntry(entryName));
                    is.transferTo(jos);
                    jos.closeEntry();
                }
            }
        }

        private void bundleCurrentProject(JarOutputStream jos) throws IOException {
            // Find where our classes are (usually target/classes)
            String resourcePath = PackagedLoomApp.class.getName().replace('.', '/') + ".class";
            java.net.URL url = PackagedLoomApp.class.getResource("/" + resourcePath);
            if (url != null && url.getProtocol().equals("file")) {
                String rootPath =
                        url.getPath().substring(0, url.getPath().length() - resourcePath.length());
                Path root = Paths.get(rootPath);
                Files.walk(root)
                        .filter(Files::isRegularFile)
                        .forEach(
                                path -> {
                                    try {
                                        String name =
                                                root.relativize(path).toString().replace('\\', '/');
                                        if (!name.equals("META-INF/MANIFEST.MF")) {
                                            try {
                                                jos.putNextEntry(new JarEntry(name));
                                                Files.copy(path, jos);
                                                jos.closeEntry();
                                            } catch (ZipException e) {
                                                // Skip duplicate
                                            }
                                        }
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                });
            }
        }

        private void bundleDependencies(JarOutputStream jos) throws IOException {
            String classpath = System.getProperty("java.class.path");
            String[] entries = classpath.split(File.pathSeparator);
            for (String entry : entries) {
                if (entry.endsWith(".jar")) {
                    // Avoid bundling the output jar if it's already there
                    if (entry.contains(outputName)) continue;

                    try (JarFile jar = new JarFile(entry)) {
                        Enumeration<JarEntry> jarEntries = jar.entries();
                        while (jarEntries.hasMoreElements()) {
                            JarEntry je = jarEntries.nextElement();
                            if (je.isDirectory()
                                    || je.getName().equals("META-INF/MANIFEST.MF")
                                    || je.getName().startsWith("META-INF/SIG-")) {
                                continue;
                            }
                            // Avoid duplicates
                            try {
                                jos.putNextEntry(new JarEntry(je.getName()));
                                try (InputStream is = jar.getInputStream(je)) {
                                    is.transferTo(jos);
                                }
                                jos.closeEntry();
                            } catch (ZipException e) {
                                // Ignore duplicate entries
                            }
                        }
                    }
                } else {
                    // It's a directory (project classes)
                    bundleCurrentProject(jos);
                }
            }
        }
    }

    /** The command line as {@code weave} runs it: every command registered. */
    static CommandLine commandLine() {
        CommandLine cli = build();
        helpEverywhere(cli);
        cli.setExecutionStrategy(WeaveCLI::executeQuietly);
        return cli;
    }

    /**
     * Commands that load the script again for every case: the loader's warnings (they are the
     * check's and the review's to report) would be printed once for each case.
     */
    private static final java.util.Set<String> ONCE_IS_ENOUGH =
            java.util.Set.of("test", "regress", "gate", "capture");

    private static int executeQuietly(CommandLine.ParseResult parseResult) {
        java.util.logging.Logger loom = java.util.logging.Logger.getLogger("io.github.llm4j.loom");
        java.util.logging.Level before = loom.getLevel();
        String command =
                parseResult.hasSubcommand() ? parseResult.subcommand().commandSpec().name() : "";
        Object root = parseResult.commandSpec().userObject();
        int verbosity = root instanceof WeaveCLI w ? w.verbosity.length : 0;
        String env = System.getenv("WEAVE_LOG");
        java.util.logging.Level level;
        if (verbosity >= 2) level = java.util.logging.Level.FINE;
        else if (verbosity == 1 || "info".equalsIgnoreCase(env))
            level = java.util.logging.Level.INFO;
        else if (ONCE_IS_ENOUGH.contains(command) && !"warn".equalsIgnoreCase(env))
            // the loader's warnings are the check's and the review's to report, not once per case
            level = java.util.logging.Level.OFF;
        else level = java.util.logging.Level.WARNING;
        loom.setLevel(level);
        if (parseResult.hasSubcommand()
                && parseResult.subcommand().commandSpec().userObject() instanceof EvalCommand e)
            e.verbose = verbosity >= 1;
        try {
            return new CommandLine.RunLast().execute(parseResult);
        } finally {
            loom.setLevel(before);
        }
    }

    /**
     * Every command, and every command under it, answers {@code --help} and {@code -h} with its
     * usage and exit code 0.
     */
    private static void helpEverywhere(CommandLine cli) {
        for (CommandLine sub : cli.getSubcommands().values()) {
            if (!sub.getCommandSpec().mixinStandardHelpOptions())
                sub.getCommandSpec().mixinStandardHelpOptions(true);
            helpEverywhere(sub);
        }
    }

    static CommandLine build() {
        return new CommandLine(new WeaveCLI())
                .addSubcommand(new RunCommand())
                .addSubcommand(new CheckCommand())
                .addSubcommand(new ResumeCommand())
                .addSubcommand(new TickCommand())
                .addSubcommand(new DaemonCommand())
                .addSubcommand(new ObserveCommand())
                .addSubcommand(new TriggersCommand())
                .addSubcommand(new ScheduleCommand())
                .addSubcommand(new PackageCommand())
                .addSubcommand(new AnswerCommands.Answer())
                .addSubcommand(new AnswerCommands.Questions())
                .addSubcommand(new TravelCommands.Timeline())
                .addSubcommand(new TravelCommands.Rewind())
                .addSubcommand(new TravelCommands.Reset())
                .addSubcommand(new TravelCommands.Fork())
                .addSubcommand(new WhyCommand())
                .addSubcommand(new WhatIfCommand())
                .addSubcommand(new StepCommand())
                .addSubcommand(new DapCommand())
                .addSubcommand(new GenerateCommand())
                .addSubcommand(new CaptureCommand())
                .addSubcommand(new RegressCommand())
                .addSubcommand(new GateCommand())
                .addSubcommand(new BindgenCommand())
                .addSubcommand(new DeployCommand())
                .addSubcommand(new TryCommand())
                .addSubcommand(new DoctorCommand())
                .addSubcommand(new AutonomyCommands())
                .addSubcommand(new LadderCliCommand())
                .addSubcommand(new ReplayCommand())
                .addSubcommand(new AuditCommand())
                .addSubcommand(new ReviewCommand())
                .addSubcommand(new RulesCommand())
                .addSubcommand(new GraphCommand())
                .addSubcommand(new SymbolsCommand())
                .addSubcommand(new ExplainCommand())
                .addSubcommand(new NextCommand())
                .addSubcommand(new EvalCommand())
                .addSubcommand(new TestCommand())
                .addSubcommand(new ServeCommand())
                .addSubcommand(new InitCommand())
                .addSubcommand(new GuideCommand())
                .addSubcommand(new ManCommand())
                .addSubcommand(new SecretCommands())
                .addSubcommand(new ToolsCommand())
                .addSubcommand(new PreflightCommand());
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /**
     * {@code weave --version}: the version and when this jar was built, from its manifest, so a jar
     * that is older than a script (or than the one beside it) can be recognized. Run from classes
     * (a test or an IDE) it says so.
     */
    /** The version number alone (or "dev" when run from classes), for the editor handshake. */
    static String versionNumber() {
        Package p = WeaveCLI.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null ? "dev" : v;
    }

    static final class BuildVersion implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {line(io.github.llm4j.BuildIdentity.ofClass(WeaveCLI.class))};
        }

        /**
         * {@code weave 6.2.0-SNAPSHOT (development, 3d683a35, built 2026-10-29T15:30:00Z), serve
         * protocol 1}: enough for a bug report to find the commit, and for the editor to read the
         * protocol.
         */
        static String line(io.github.llm4j.BuildIdentity identity) {
            if (identity.fromClasses()) return "weave (run from classes, not a built jar)";
            return "weave "
                    + identity.describe()
                    + ", serve protocol "
                    + io.github.llm4j.loom.serve.Server.PROTOCOL;
        }
    }
}
