package io.github.llm4j.loom.cli;

import io.github.llm4j.agent.skill.AgentSkill;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.*;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.LoomEngine;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.jar.*;
import java.util.zip.*;

@Command(name = "weave", mixinStandardHelpOptions = true, version = "weave 1.0",
        description = "Loom Orchestration CLI - Weave workflows into executable reality.")
public class WeaveCLI implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "run", description = "Executes a .loom script immediately.")
    static class RunCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The .loom script file to execute.")
        private File scriptFile;

        @Option(names = {"-l", "--loot"}, description = "The .loot tool mapping file.")
        private File lootFile;

        @Option(names = {"-w", "--workflow"}, description = "The name of the workflow to run.", defaultValue = "Main")
        private String workflowName;

        @Option(names = {"-i", "--input"}, description = "Initial context variables in key=value format.")
        private Map<String, String> inputs = new HashMap<>();

        @Option(names = "--max-tokens", description = "Cap the run's tokens (replaces the script's run budget).")
        private Long maxTokens;

        @Option(names = "--max-calls", description = "Cap the run's LLM calls (replaces the script's run budget).")
        private Long maxCalls;

        @Option(names = "--max-cost", description = "Cap the run's cost, e.g. 0.50 (needs --prices).")
        private String maxCost;

        @Option(names = "--prices", description = "Price table: lines of 'model = input / output' per million tokens.")
        private File prices;

        @Option(names = "--journal", description = "Run directory: makes the run durable, so it can pause on limits and resume later.")
        private File journal;

        @Option(names = "--store", description = "Trigger store for resumes (default: <journal>/../.loom-triggers).")
        private File store;

        @Option(names = "--wait", description = "When the run pauses, stay alive and resume it when its limit resets.")
        private boolean waitForResume;

        @Option(names = "--lenient", description = "Treat features that aren't supported yet as warnings, not errors.")
        private boolean lenient;

        @Option(names = "--stop-at", description = "Stop cleanly once this step (or checkpoint) has completed; resume the run to carry on.")
        private String stopAt;

        @Option(names = "--max-rewinds", description = "The most times the run may go back in all (default 20).")
        private Integer maxRewinds;

        @Option(names = "--ask-via", paramLabel = "telegram|command|console", description = "Where to ask a person (default: the store's channel.json, else the console).")
        private String askVia;

        @Option(names = "--trace", arity = "0..1", fallbackValue = "text", paramLabel = "text|json",
                description = "Show what agents think and do, live, on stderr (--trace=json for JSON lines).")
        private String trace;

        @CommandLine.Mixin
        private SecretOptions secrets = new SecretOptions();

        @CommandLine.Mixin
        private PromptOptions promptOptions = new PromptOptions();

        @Override
        public Integer call() throws Exception {
            if (!scriptFile.exists()) {
                System.err.println("Error: Script file not found: " + scriptFile);
                return 1;
            }
            WeaveEnv env = secrets.apply(WeaveEnv.system().withAskVia(askVia), Prompts.console());
            if (env == null) return 2;
            env = promptOptions.apply(env);
            if (env == null) return 2;
            return run(scriptFile, lootFile, workflowName, inputs, maxTokens, maxCalls, maxCost, prices,
                    journal == null ? null : journal.toPath(), store == null ? null : store.toPath(), waitForResume,
                    lenient, trace, stopAt, maxRewinds, env);
        }
    }

    /**
     * Runs a workflow the way {@code weave run} does, with an explicit client factory and output streams
     * (so it can be tested). Exit codes: 0 done, 1 failed, 2 bad budget options, 3 stopped by a budget,
     * 4 paused.
     */
    static int execute(File scriptFile, File lootFile, String workflowName, Map<String, String> inputs,
                       LLMClientFactory clientFactory, Long maxTokens, Long maxCalls, String maxCost, File pricesFile,
                       io.github.llm4j.loom.runtime.HumanInterface human, java.io.PrintStream out,
                       java.io.PrintStream err) throws Exception {
        WeaveEnv env = new WeaveEnv(clientFactory, human, out, err, java.time.Clock.systemDefaultZone(),
                io.github.llm4j.ratelimit.Sleeper.SYSTEM, io.github.llm4j.loom.trigger.system.CommandRunner.SYSTEM,
                WeaveEnv.selfCommand());
        return run(scriptFile, lootFile, workflowName, inputs, maxTokens, maxCalls, maxCost, pricesFile, null, null,
                false, env);
    }

    static int run(File scriptFile, File lootFile, String workflowName, Map<String, String> inputs, Long maxTokens,
                   Long maxCalls, String maxCost, File pricesFile, Path journal, Path store, boolean wait, WeaveEnv env) {
        return run(scriptFile, lootFile, workflowName, inputs, maxTokens, maxCalls, maxCost, pricesFile, journal, store,
                wait, false, env);
    }

    static int run(File scriptFile, File lootFile, String workflowName, Map<String, String> inputs, Long maxTokens,
                   Long maxCalls, String maxCost, File pricesFile, Path journal, Path store, boolean wait,
                   boolean lenient, WeaveEnv env) {
        return run(scriptFile, lootFile, workflowName, inputs, maxTokens, maxCalls, maxCost, pricesFile, journal, store,
                wait, lenient, null, env);
    }

    static int run(File scriptFile, File lootFile, String workflowName, Map<String, String> inputs, Long maxTokens,
                   Long maxCalls, String maxCost, File pricesFile, Path journal, Path store, boolean wait,
                   boolean lenient, String trace, WeaveEnv env) {
        return run(scriptFile, lootFile, workflowName, inputs, maxTokens, maxCalls, maxCost, pricesFile, journal, store, wait, lenient, trace, null, env);
    }

    static int run(File scriptFile, File lootFile, String workflowName, Map<String, String> inputs, Long maxTokens,
                   Long maxCalls, String maxCost, File pricesFile, Path journal, Path store, boolean wait,
                   boolean lenient, String trace, String stopAt, WeaveEnv env) {
        return run(scriptFile, lootFile, workflowName, inputs, maxTokens, maxCalls, maxCost, pricesFile, journal, store, wait, lenient, trace, stopAt, null, env);
    }

    static int run(File scriptFile, File lootFile, String workflowName, Map<String, String> inputs, Long maxTokens,
                   Long maxCalls, String maxCost, File pricesFile, Path journal, Path store, boolean wait,
                   boolean lenient, String trace, String stopAt, Integer maxRewinds, WeaveEnv env) {
        if (trace != null && !trace.equals("text") && !trace.equals("json")) {
            env.err().println("Error: --trace takes text or json, got " + trace);
            return 2;
        }
        Path runDir = journal == null ? null : journal.toAbsolutePath().normalize();
        Path storeDir = runDir == null ? null
                : (store != null ? store.toAbsolutePath().normalize() : Runs.defaultStore(runDir));
        RunSpec spec = new RunSpec(scriptFile.getAbsolutePath(), lootFile == null ? null : lootFile.getAbsolutePath(),
                workflowName, inputs, maxTokens, maxCalls, maxCost,
                pricesFile == null ? null : pricesFile.getAbsolutePath(), storeDir == null ? null : storeDir.toString(),
                lenient, trace).withPrompts(env.prompts());
        if (runDir != null) spec.write(runDir);
        Runs.Result result = Runs.execute(spec.withStopAt(stopAt).withMaxRewinds(maxRewinds), runDir, null, env);
        if (result.exit() == 4 && wait && runDir != null && result.resumeAt() != null) {
            return waitForRun(runDir, storeDir, env);
        }
        return result.exit();
    }

    /** {@code --wait}: sleeps until the run's resume trigger is due, resumes it, and repeats until it ends. */
    static int waitForRun(Path runDir, Path storeDir, WeaveEnv env) {
        io.github.llm4j.loom.trigger.FileTriggerStore store = new io.github.llm4j.loom.trigger.FileTriggerStore(storeDir);
        String id = Runs.resumeTriggerId(runDir);
        io.github.llm4j.loom.trigger.TriggerTarget.Outcome[] last = new io.github.llm4j.loom.trigger.TriggerTarget.Outcome[1];
        io.github.llm4j.loom.trigger.TriggerRunner runner = new io.github.llm4j.loom.trigger.TriggerRunner(store,
                new WeaveTriggerTarget(storeDir, env), env.clock()).onFired(f -> {
                    if (f.trigger().id().equals(id)) last[0] = f.outcome();
                });
        for (int round = 0; round < 10_000; round++) {
            var pending = store.get(id);
            if (pending.isEmpty()) break;
            java.time.Duration wait = java.time.Duration.between(env.clock().instant(), pending.get().nextFire());
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

    @Command(name = "check", description = "Checks a script — tools, knowledge, secrets, names — without running it.")
    static class CheckCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The .loom script to check.")
        private File scriptFile;

        @Option(names = {"-l", "--loot"}, description = "The .loot tool mapping file.")
        private File lootFile;

        @Option(names = "--lenient", description = "Treat features that aren't supported yet as warnings.")
        private boolean lenient;

        @Option(names = "--format", description = "text (default) or json: one JSON object with the problems in it, for tools (the editor reads this).", defaultValue = "text")
        private String format;

        @Option(names = "--no-env", description = "Check the script without keys: names that are not set in the environment are listed as 'not set yet' instead of being problems.")
        private boolean noEnv;

        @Option(names = "--strict", description = "Treat warnings as errors (exit 2), so a build can require a clean script.")
        private boolean strict;

        @CommandLine.Mixin
        private SecretOptions secrets = new SecretOptions();

        @CommandLine.Mixin
        private PromptOptions promptOptions = new PromptOptions();

        @Override
        public Integer call() {
            WeaveEnv env = secrets.apply(WeaveEnv.system(), Prompts.console());
            if (env == null) return 2;
            env = promptOptions.apply(env);
            if (env == null) return 2;
            if (!format.equals("text") && !format.equals("json")) {
                System.err.println("Error: --format takes text or json.");
                return 2;
            }
            return check(scriptFile, lootFile, new CheckSettings(lenient, format.equals("json"), noEnv, strict), env);
        }
    }

    /** What {@code weave check} was asked to do besides check. */
    record CheckSettings(boolean lenient, boolean json, boolean noEnv, boolean strict) {
        static final CheckSettings DEFAULT = new CheckSettings(false, false, false, false);
    }

    /**
     * Runs every load-time check without calling a model, embedding or starting a server. Prints every
     * problem with its line; exit 0 when there are no errors, 2 otherwise. Never prints secret values.
     */
    static int check(File scriptFile, File lootFile, boolean lenient, WeaveEnv env) {
        return check(scriptFile, lootFile, new CheckSettings(lenient, false, false, false), env);
    }

    private static final java.util.regex.Pattern NEEDS_KEY = java.util.regex.Pattern.compile("needs ([A-Za-z0-9_]+) in the environment");
    private static final java.util.regex.Pattern LINE_OF = java.util.regex.Pattern.compile("line (\\d+)");

    static int check(File scriptFile, File lootFile, CheckSettings settings, WeaveEnv env) {
        io.github.llm4j.loom.ast.LoomScript script;
        try {
            script = new LoomLoader().load(scriptFile.getAbsolutePath());
        } catch (Exception e) {
            if (settings.json()) {
                var m = LINE_OF.matcher(String.valueOf(e.getMessage()));
                int line = m.find() ? Integer.parseInt(m.group(1)) : 0;
                env.out().println(checkJson(scriptFile, List.of(new JsonProblem("error", line, String.valueOf(e.getMessage()))), List.of()));
            } else {
                env.out().println("✗ " + scriptFile.getName() + ": " + e.getMessage());
            }
            return 2;
        }
        ToolRegistry registry = new ToolRegistry();
        if (lootFile != null && lootFile.exists()) new LootLoader().loadIntoRegistry(lootFile.getAbsolutePath(), registry);
        LLMClientFactory models = env.models();
        java.util.Set<String> notSetYet = new java.util.TreeSet<>();
        HarnessExecutor executor = new HarnessExecutor(script, registry, new LLMClientFactory() {
            @Override
            public io.github.llm4j.LLMClient createClient(String model) {
                throw new IllegalStateException("weave check never creates model clients");
            }

            @Override
            public String problem(String model) {
                String problem = models.problem(model); // names and keys are checked; nothing is contacted
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
        executor.setBaseDir(scriptFile.getAbsoluteFile().getParentFile().toPath());
        java.util.function.Function<String, String> lookup = env.env();
        executor.setEnvLookup(settings.noEnv() ? name -> {
            String value = lookup.apply(name);
            if (value == null) {
                notSetYet.add(name);
                return "not-set-yet";
            }
            return value;
        } : lookup);
        executor.setSecretStore(env.secrets());
        executor.setPromptCatalog(io.github.llm4j.loom.prompt.PromptSupport.catalog(script, scriptFile.toPath(), env.prompts()));
        executor.setHumanInterface(env.human()); // the CLI always has a console
        List<io.github.llm4j.loom.execution.ScriptValidator.Problem> problems =
                new io.github.llm4j.loom.execution.ScriptValidator().validate(script, executor.validationContext());
        var error = io.github.llm4j.loom.execution.ScriptValidator.Severity.ERROR;
        long errors = problems.stream().filter(p -> p.severity() == error || settings.strict()).count();
        if (settings.json()) {
            List<JsonProblem> out = new ArrayList<>();
            for (var p : problems) out.add(new JsonProblem(p.severity() == error || settings.strict() ? "error" : "warning", p.line(),
                    (p.construct() == null ? "" : p.construct() + ": ") + p.message()));
            env.out().println(checkJson(scriptFile, out, new ArrayList<>(notSetYet)));
            return errors == 0 ? 0 : 2;
        }
        for (var p : problems) env.out().println((p.severity() == error || settings.strict() ? "✗ " : "⚠ ") + p);
        if (!notSetYet.isEmpty()) env.out().println("ℹ not set yet (needed to run): " + String.join(", ", notSetYet));
        if (errors == 0) {
            env.out().println("✓ " + scriptFile.getName() + ": ready to run"
                    + (problems.isEmpty() ? "" : " (" + problems.size() + " warning" + (problems.size() == 1 ? "" : "s") + ")"));
            return 0;
        }
        boolean keys = problems.stream().anyMatch(p -> p.message().contains("is not set") || p.message().contains("in the environment"));
        if (keys && !settings.noEnv()) env.out().println("Some of these are names that are not set yet. Set them, or use --no-env to check the script without keys.");
        env.out().println(errors + " problem" + (errors == 1 ? "" : "s") + " in " + scriptFile.getName());
        return 2;
    }

    /** One problem as the JSON of {@code weave check --format json} carries it. */
    private record JsonProblem(String severity, int line, String message) {}

    /** The JSON object: version 1, whether the script is fine, the problems, and the names not set yet. Printed with exit 0 or 2, like the text form. */
    private static String checkJson(File scriptFile, List<JsonProblem> problems, List<String> notSetYet) {
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
            return new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Command(name = "resume", description = "Resumes a paused run now, from its run directory.")
    static class ResumeCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The run directory (as given to run --journal).")
        private File runDir;

        @Option(names = "--stop-at", description = "Stop cleanly once this step (or checkpoint) has completed.")
        private String stopAt;

        @Option(names = "--ask-via", paramLabel = "telegram|command|console", description = "Where to ask a person (default: the store's channel.json, else the console).")
        private String askVia;

        @CommandLine.Mixin
        private SecretOptions secrets = new SecretOptions();

        @Override
        public Integer call() {
            WeaveEnv env = secrets.apply(WeaveEnv.system().withAskVia(askVia), Prompts.console());
            if (env == null) return 2;
            return resume(runDir.toPath(), stopAt, env);
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
            new io.github.llm4j.loom.trigger.FileTriggerStore(Path.of(spec.store())).remove(Runs.resumeTriggerId(dir));
        }
        return r.exit();
    }

    @Command(name = "tick", description = "Fires every trigger that is due in a store, then exits (for cron, systemd, launchd).")
    static class TickCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The trigger store directory.")
        private File store;

        @Option(names = "--ask-via", paramLabel = "telegram|command|console", description = "Where to ask a person (default: the store's channel.json, else the console).")
        private String askVia;

        @CommandLine.Mixin
        private SecretOptions secrets = new SecretOptions();

        @Override
        public Integer call() {
            WeaveEnv env = secrets.apply(WeaveEnv.system().withAskVia(askVia), Prompts.console());
            if (env == null) return 2;
            return tick(store.toPath(), env);
        }
    }

    static int tick(Path storeDir, WeaveEnv env) {
        Path dir = storeDir.toAbsolutePath().normalize();
        io.github.llm4j.loom.trigger.FileTriggerStore store = new io.github.llm4j.loom.trigger.FileTriggerStore(dir);
        java.util.Optional<io.github.llm4j.loom.channel.Channels.Runtime> channel;
        try {
            channel = io.github.llm4j.loom.channel.Channels.open(dir, env.askVia(), env.env(), env.clock());
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        if (channel.isPresent()) {
            // answers that arrived since the last tick are recorded first (each leaves a resume trigger), then what is unsent, due a reminder or expired is dealt with
            try {
                var summary = channel.get().listener().pollOnce(java.time.Duration.ZERO);
                if (summary.recorded() > 0) env.out().println("💬 Recorded " + summary.recorded() + " answer(s)");
                channel.get().listener().maintain();
            } catch (java.io.IOException | RuntimeException e) {
                env.err().println("⚠ The channel could not be read this time (" + e.getMessage() + "); questions already sent are still waiting.");
            }
        }
        int fired = new io.github.llm4j.loom.trigger.TriggerRunner(store, new WeaveTriggerTarget(dir, env), env.clock()).tick();
        if (fired > 0) env.out().println("🔔 Fired " + fired + " trigger(s)");
        Triggers.resyncExact(dir, store, env);
        return 0;
    }

    @Command(name = "daemon", description = "Keeps running and fires triggers from a store as they come due.")
    static class DaemonCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The trigger store directory.")
        private File store;

        @Option(names = "--poll", description = "How often to look for due triggers, e.g. 5s.", defaultValue = "5s")
        private String poll;

        @Option(names = "--ask-via", paramLabel = "telegram|command|console", description = "Where to ask a person (default: the store's channel.json, else the console).")
        private String askVia;

        @CommandLine.Mixin
        private SecretOptions secrets = new SecretOptions();

        @Override
        public Integer call() throws Exception {
            WeaveEnv env = secrets.apply(WeaveEnv.system().withAskVia(askVia), Prompts.console());
            if (env == null) return 2;
            return daemon(store.toPath(), io.github.llm4j.loom.trigger.Schedules.parse(poll), null, env);
        }
    }

    static int daemon(Path storeDir, java.time.Duration poll, java.time.Duration runFor, WeaveEnv env) throws InterruptedException {
        Path dir = storeDir.toAbsolutePath().normalize();
        io.github.llm4j.loom.trigger.TriggerRunner runner = new io.github.llm4j.loom.trigger.TriggerRunner(
                new io.github.llm4j.loom.trigger.FileTriggerStore(dir), new WeaveTriggerTarget(dir, env), env.clock());
        java.util.Optional<io.github.llm4j.loom.channel.Channels.Runtime> channel;
        try {
            channel = io.github.llm4j.loom.channel.Channels.open(dir, env.askVia(), env.env(), env.clock());
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        env.out().println("👂 Watching " + dir + " (every " + HarnessExecutor.human(poll) + ")" + (channel.isPresent() ? " and listening on " + channel.get().channel().name() : "") + ". Ctrl+C to stop.");
        java.util.concurrent.atomic.AtomicBoolean stopListening = new java.util.concurrent.atomic.AtomicBoolean();
        Thread listener = null;
        if (channel.isPresent()) {
            var rt = channel.get();
            listener = new Thread(() -> rt.listen(java.time.Duration.ofSeconds(runFor == null ? 30 : 1), stopListening,
                    last -> last.isZero() ? java.time.Duration.ofSeconds(5) : last.multipliedBy(2).compareTo(java.time.Duration.ofMinutes(5)) > 0 ? java.time.Duration.ofMinutes(5) : last.multipliedBy(2)), "loom-channel");
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

    @Command(name = "triggers", description = "Lists and manages stored triggers, and installs system triggers.%n"
            + "  list | pause <id> | enable <id> | cancel <id> | fire <id> | install | uninstall")
    static class TriggersCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "list, pause, enable, cancel, fire, install or uninstall.")
        private String action;

        @Parameters(index = "1", description = "The trigger store directory.")
        private File store;

        @Parameters(index = "2", arity = "0..1", description = "The trigger id (pause, enable, cancel, fire).")
        private String id;

        @Option(names = "--backend", description = "cron, systemd, launchd, windows or cloud-scheduler (default: detected).")
        private String backend;

        @Option(names = "--mode", description = "heartbeat (default) or exact.", defaultValue = "heartbeat")
        private String mode;

        @Option(names = "--every", description = "Heartbeat interval, e.g. 5m.", defaultValue = "5m")
        private String every;

        @Option(names = "--apply", description = "Make the change (without it, only show what would change).")
        private boolean apply;

        @Option(names = "--weave", description = "The command that runs weave (default: this Java and classpath).")
        private String weave;

        @Option(names = "--env-file", description = "A file of KEY=VALUE lines (API keys) loaded before each tick.")
        private File envFile;

        @Option(names = "--url", description = "cloud-scheduler: the service URL exposing /loom/tick.")
        private String url;

        @Option(names = "--service-account", description = "cloud-scheduler: service account for the OIDC token.")
        private String serviceAccount;

        @Option(names = "--region", description = "cloud-scheduler: location, e.g. asia-south1.")
        private String region;

        @Override
        public Integer call() throws Exception {
            WeaveEnv env = WeaveEnv.system();
            if (weave != null) env = env.withWeave(List.of(weave.trim().split("\\s+")));
            Triggers.InstallOptions options = new Triggers.InstallOptions(backend, mode, every, apply,
                    envFile == null ? null : envFile.toPath(), url, serviceAccount, region,
                    Path.of(System.getProperty("user.home")), System.getProperty("os.name"));
            return Triggers.run(action, store.toPath(), id, options, env);
        }
    }

    @Command(name = "schedule", description = "schedule sync <script> --store <dir>: writes a script's schedule blocks to a trigger store.")
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
                System.err.println("Error: unknown action '" + action + "'. Use: weave schedule sync <script> --store <dir>");
                return 2;
            }
            return Triggers.syncSchedules(script.toPath(), store.toPath(), WeaveEnv.system());
        }
    }

    @Command(name = "package", description = "Packages a .loom workflow into a runnable JAR.")
    static class PackageCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The .loom script file.")
        private File scriptFile;

        @Option(names = {"-l", "--loot"}, description = "The .loot tool mapping file.")
        private File lootFile;

        @Option(names = {"-o", "--out"}, description = "Output JAR filename.", defaultValue = "loom-app.jar")
        private String outputName;

        @Option(names = {"--fat"}, description = "Create a fat JAR containing all dependencies.")
        private boolean fatJar;

        @Option(names = {"--thin"}, description = "Create a thin JAR (default).", defaultValue = "true")
        private boolean thinJar;

        @Override
        public Integer call() throws Exception {
            if (!scriptFile.exists()) {
                System.err.println("Error: Script file not found: " + scriptFile);
                return 1;
            }

            File outFile = new File(outputName);
            System.out.println("📦 Packaging workflow into: " + outFile.getAbsolutePath());

            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, PackagedLoomApp.class.getName());

            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outFile), manifest)) {
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
                    // bundleCurrentProject adds all classes in the current classpath entry for this project
                    bundleCurrentProject(jos);
                }
            }

            System.out.println("✅ Packaging complete: " + outputName);
            return 0;
        }

        private void addFileToJar(JarOutputStream jos, File file, String entryName) throws IOException {
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
                String rootPath = url.getPath().substring(0, url.getPath().length() - resourcePath.length());
                Path root = Paths.get(rootPath);
                Files.walk(root).filter(Files::isRegularFile).forEach(path -> {
                    try {
                        String name = root.relativize(path).toString().replace('\\', '/');
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
                            if (je.isDirectory() || je.getName().equals("META-INF/MANIFEST.MF") || je.getName().startsWith("META-INF/SIG-")) {
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
        return new CommandLine(new WeaveCLI())
                .addSubcommand(new RunCommand())
                .addSubcommand(new CheckCommand())
                .addSubcommand(new ResumeCommand())
                .addSubcommand(new TickCommand())
                .addSubcommand(new DaemonCommand())
                .addSubcommand(new TriggersCommand())
                .addSubcommand(new ScheduleCommand())
                .addSubcommand(new PackageCommand())
                .addSubcommand(new AnswerCommands.Answer())
                .addSubcommand(new AnswerCommands.Questions())
                .addSubcommand(new TravelCommands.Timeline())
                .addSubcommand(new TravelCommands.Rewind())
                .addSubcommand(new TravelCommands.Reset())
                .addSubcommand(new TravelCommands.Fork())
                .addSubcommand(new AutonomyCommands())
                .addSubcommand(new ReplayCommand())
                .addSubcommand(new AuditCommand())
                .addSubcommand(new GraphCommand())
                .addSubcommand(new EvalCommand())
                .addSubcommand(new SecretCommands());
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }
}
