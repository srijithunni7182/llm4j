package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.LootLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.loom.trigger.FileTriggerStore;
import io.github.llm4j.loom.trigger.Trigger;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Runs a workflow the way the {@code weave} commands do, optionally as a durable run in a run directory. */
final class Runs {

    /** How a run ended: exit code 0 done, 1 failed, 2 bad options, 3 stopped by a budget, 4 paused, 5 stopped where it was told to. */
    record Result(int exit, Instant resumeAt, RunSuspended.Reason reason, String message) { }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private Runs() { }

    /** The default trigger store for a run directory: {@code <run dir>/../.loom-triggers}. */
    static Path defaultStore(Path runDir) {
        Path parent = runDir.toAbsolutePath().normalize().getParent();
        return (parent != null ? parent : runDir.toAbsolutePath()).resolve(".loom-triggers");
    }

    /** Where a scheduled run (id {@code <schedule>@<slot>}) keeps its run directory. */
    static Path scheduledRunDir(Path store, String runId) {
        return store.resolve("runs").resolve(runId.replaceAll("[^A-Za-z0-9._@-]", "-"));
    }

    /**
     * @param runDir the run directory (journal and run.json), or null for a run that can't be resumed later
     * @param runId  how resume triggers name this run (null: the run directory's path)
     */
    static Result execute(RunSpec spec, Path runDir, String runId, WeaveEnv env) {
        java.util.Optional<RunLock> lock = java.util.Optional.empty();
        if (runDir != null) {
            lock = RunLock.tryAcquire(runDir);
            if (lock.isEmpty()) {
                env.err().println("Error: process " + RunLock.holder(runDir).orElse(-1L) + " is already working on " + runDir + ".");
                return new Result(2, null, null, "run is locked");
            }
        }
        try {
            return executeLocked(spec, runDir, runId, env);
        } finally {
            lock.ifPresent(RunLock::close);
        }
    }

    /**
     * The price table for a run: the file given with {@code --prices}; else {@code <script>.prices} beside the script; else
     * {@code prices.properties} in the script's folder; else the file named by {@code LOOM_PRICES}; else none (cost is then not reported).
     * Looking beside the script means {@code daemon}, {@code tick} and {@code resume} price a workflow without a flag.
     */
    static Path pricesFile(RunSpec spec, File scriptFile, WeaveEnv env) {
        if (spec.prices() != null) return Path.of(spec.prices());
        File dir = scriptFile.getAbsoluteFile().getParentFile();
        File beside = new File(dir, baseName(scriptFile) + ".prices");
        if (beside.isFile()) return beside.toPath();
        File shared = new File(dir, "prices.properties");
        if (shared.isFile()) return shared.toPath();
        String fromEnv = env.env() == null ? null : env.env().apply("LOOM_PRICES");
        if (fromEnv != null && !fromEnv.isBlank() && new File(fromEnv.strip()).isFile()) return Path.of(fromEnv.strip());
        return null;
    }

    private static String baseName(File f) {
        return f.getName().replaceFirst("\\.[^.]*$", "");
    }

    private static Result executeLocked(RunSpec spec, Path runDir, String runId, WeaveEnv env) {
        java.math.BigDecimal cost = null;
        Path pricesFile = pricesFile(spec, new File(spec.script()), env);
        if (spec.maxCost() != null) {
            if (pricesFile == null) {
                env.err().println("Error: --max-cost needs prices: --prices <file>, or a file named " + baseName(new File(spec.script())) + ".prices or prices.properties beside the script, or LOOM_PRICES"
                        + " (lines of 'model = input / output' per million tokens).");
                return new Result(2, null, null, "bad options");
            }
            cost = new java.math.BigDecimal(spec.maxCost().strip().replaceFirst("^\\$", ""));
        }
        if ((spec.maxTokens() != null && spec.maxTokens() <= 0) || (spec.maxCalls() != null && spec.maxCalls() <= 0)
                || (cost != null && cost.signum() <= 0)) {
            env.err().println("Error: budget limits must be positive.");
            return new Result(2, null, null, "bad options");
        }
        File scriptFile = new File(spec.script());
        env.out().println("🧵 Weaving workflow: " + scriptFile.getName());
        HarnessExecutor executor;
        try {
            LoomScript script = new LoomLoader().load(scriptFile.getAbsolutePath());
            ToolRegistry registry = new ToolRegistry();
            if (spec.loot() != null && new File(spec.loot()).exists()) {
                new LootLoader().loadIntoRegistry(new File(spec.loot()).getAbsolutePath(), registry);
                env.out().println("🛠️  Loaded tools from: " + new File(spec.loot()).getName());
            }
            executor = new HarnessExecutor(script, registry, env.models());
            executor.setHumanInterface(env.human());
            executor.setLenient(spec.lenient());
            executor.setBaseDir(scriptFile.getAbsoluteFile().getParentFile().toPath());
            executor.setEnvLookup(env.env());
            executor.setSecretStore(env.secrets());
            executor.setPromptCatalog(io.github.llm4j.loom.prompt.PromptSupport.catalog(script, scriptFile.toPath(), spec.prompts()));
            executor.setClock(env.clock());
            executor.setSleeper(env.sleeper());
            if (pricesFile != null) executor.setPriceTable(io.github.llm4j.budget.PriceTable.load(pricesFile));
            executor.setBudgetOverrides(spec.maxTokens(), spec.maxCalls(), cost);
            if (spec.trace() != null) executor.addTraceListener(new ConsoleTrace(env.err(), "json".equals(spec.trace())));
            if (env.observe() != null) env.observe().attach(executor.observable(), scriptFile.toPath()); // before initialize, so agents report
            executor.setSimulate(spec.simulate());
            executor.setStopAt(spec.stopAt());
            if (spec.maxRewinds() != null) executor.setMaxRewinds(spec.maxRewinds());
            if (runDir == null && env.askVia() != null && !"console".equals(env.askVia())) {
                throw new IllegalArgumentException("--ask-via " + env.askVia() + " needs --journal: a run that cannot be resumed cannot wait for an answer");
            }
            if (runDir != null) {
                executor.setJournal(new FileRunJournal(runDir.resolve("journal.json")));
                executor.setTriggerStore(new FileTriggerStore(Path.of(spec.store())));
                executor.setRunId(runId != null ? runId : runDir.toAbsolutePath().normalize().toString());
                executor.setScriptRef(scriptFile.getAbsolutePath());
                executor.setRunLocator(runDir.toAbsolutePath().normalize().toString());
                // a channel (a chat, a bridge) means the person is not at this console: the run asks, suspends, and carries on when the answer is recorded
                Path questionStore = Path.of(spec.store());
                String askId = runId != null ? runId : runDir.toAbsolutePath().normalize().toString();
                java.util.Optional<io.github.llm4j.loom.channel.Channels.Runtime> channel = io.github.llm4j.loom.channel.Channels.open(questionStore, env.askVia(), env.env(), env.clock());
                if (channel.isPresent()) {
                    executor.setHumanInterface(channel.get().humanFor(runDir, askId));
                } else if (new io.github.llm4j.loom.channel.PendingStore(questionStore).exists()) {
                    executor.setHumanInterface(new io.github.llm4j.loom.channel.ClosingHumanInterface(env.human(), questionStore, runDir.toAbsolutePath().normalize().toString(), env.clock()));
                }
                if (!script.getDecisions().isEmpty()) {
                    // decisions keep their ledger and levels in the run store, next to the triggers
                    Path store = Path.of(spec.store());
                    executor.setAutonomy(AutonomySupport.ledger(store), AutonomySupport.levels(store));
                    AutonomySupport.rememberScript(store, script, scriptFile);
                    Path scriptPath = scriptFile.toPath().toAbsolutePath();
                    executor.setInheritance(new io.github.llm4j.loom.execution.HarnessExecutor.Inheritance() {
                        @Override
                        public io.github.llm4j.loom.execution.HarnessExecutor.Inherited inherited(io.github.llm4j.loom.ast.DecisionDef def, String scope,
                                io.github.llm4j.loom.autonomy.LevelState old, String identity) {
                            return AutonomySupport.inheritance(store, def.getName(), scriptPath, env).inherited(def, scope, old, identity);
                        }

                        @Override
                        public io.github.llm4j.loom.execution.HarnessExecutor.Inherited inheritedRung(io.github.llm4j.loom.ast.DecisionDef def, String scope, String oldRung,
                                int oldEpoch, String identity) {
                            return AutonomySupport.inheritance(store, def.getName(), scriptPath, env).inheritedRung(def, scope, oldRung, oldEpoch, identity);
                        }
                    });
                }
            }
            executor.initialize();
            if (runDir != null) writePromptManifest(runDir, executor, script);
        } catch (Exception e) {
            env.err().println("❌ Could not start: " + e.getMessage());
            return new Result(1, null, null, e.getMessage());
        }

        env.out().println("🚀 Executing workflow: " + spec.workflow() + "...");
        try {
            executor.executeWorkflow(spec.workflow(), spec.inputs());
            env.out().println("✅ Workflow completed successfully.");
            return new Result(0, null, null, null);
        } catch (RunSuspended paused) {
            printPaused(paused, runDir, spec, env);
            return new Result(4, paused.resumeAt(), paused.reason(), paused.getMessage());
        } catch (io.github.llm4j.loom.runtime.RunStopped stopped) {
            env.out().println("⏹ Stopped at " + stopped.point() + " as asked. Nothing is lost: resume the run to carry on" + (runDir != null ? ": weave resume " + quote(runDir.toString()) : "") + ".");
            return new Result(5, null, null, stopped.getMessage());
        } catch (io.github.llm4j.budget.BudgetExceeded stop) {
            env.out().println("⛔ Stopped: " + stop.getMessage() + ". Everything paid for so far is kept.");
            return new Result(3, null, null, stop.getMessage());
        } catch (Exception e) {
            env.err().println("❌ Execution failed: " + e.getMessage());
            e.printStackTrace(env.err());
            return new Result(1, null, null, e.getMessage());
        } finally {
            if (executor.getRunBudget() != null) {
                env.out().println();
                env.out().println("💸 Spend");
                env.out().print(executor.spend().table());
                if (runDir != null) {
                    var t = io.github.llm4j.loom.travel.RunTravel.timeline(executor.getJournal());
                    if (t.discardedTokens() > 0) {
                        env.out().println("   of which " + t.discardedTokens() + " tokens (" + t.discardedCost().toPlainString() + ") went into attempts that were later replaced by a rewind");
                    }
                }
            }
            executor.shutdown();
        }
    }

    private static void printPaused(RunSuspended paused, Path runDir, RunSpec spec, WeaveEnv env) {
        String resume = runDir != null ? "weave resume " + quote(runDir.toString()) : null;
        if (paused.reason() == RunSuspended.Reason.OPERATOR) {
            env.out().println("⏸ Suspended at " + paused.stepId() + " (" + paused.prompt() + "). Nothing is lost"
                    + (runDir != null ? "; to carry on: weave resume " + quote(runDir.toString()) : "; this run has no --journal, so it cannot be resumed later") + ".");
            return;
        }
        if (paused.resumeAt() == null) {
            env.out().println("⏸ Waiting for a person at " + paused.stepId() + ": " + paused.prompt());
            if (resume != null) {
                env.out().println("   Record the answer under that step in " + runDir.resolve("journal.json")
                        + ", then run: " + resume);
            }
            return;
        }
        ZonedDateTime at = paused.resumeAt().atZone(zone(env));
        Duration in = Duration.between(env.clock().instant(), paused.resumeAt());
        env.out().println((paused.reason() == RunSuspended.Reason.SLEEP ? "💤 Sleeping: " + paused.detail() : "⏸ Paused: " + (paused.limit() != null ? paused.limit().describe() : paused.reason()))
                + (paused.reason() == RunSuspended.Reason.SLEEP ? ". Wakes at " : ". Resumes at ") + WHEN.format(at) + " (in " + HarnessExecutor.human(in.isNegative() ? Duration.ZERO : in) + ").");
        if (runDir == null) {
            env.out().println("   This run has no --journal, so it can't be resumed later: run it again with --journal <dir>.");
            return;
        }
        String system = installedSystemTrigger(Path.of(spec.store()));
        if (system != null) {
            env.out().println("   " + system + " will resume it. To resume now: " + resume);
        } else {
            env.out().println("   No system trigger is installed for " + spec.store() + ": run `weave triggers install "
                    + quote(spec.store()) + " --apply` (or keep `weave daemon " + quote(spec.store())
                    + "` running), or resume yourself: " + resume);
        }
    }

    /** "A system trigger (systemd, every 5m)" if one was installed for the store, else null. */
    @SuppressWarnings("unchecked")
    static String installedSystemTrigger(Path store) {
        Path file = store.resolve("system.json");
        if (!Files.exists(file)) return null;
        try {
            Map<String, Object> m = new ObjectMapper().readValue(file.toFile(), Map.class);
            return "A system trigger (" + m.get("backend") + ", " + String.valueOf(m.get("mode")).toLowerCase()
                    + " every " + m.get("every") + ")";
        } catch (Exception e) {
            return null;
        }
    }

    /** The clock's zone for display ("UTC" rather than "Z"). */
    static java.time.ZoneId zone(WeaveEnv env) {
        java.time.ZoneId z = env.clock().getZone();
        return z instanceof java.time.ZoneOffset o && o.getTotalSeconds() == 0 ? java.time.ZoneId.of("UTC") : z;
    }

    static String quote(String s) {
        return s.matches("[A-Za-z0-9_@%+=:,./-]+") ? s : "'" + s.replace("'", "'\\''") + "'";
    }

    /** The resume trigger id for a run directory run by {@code weave run --journal}. */
    static String resumeTriggerId(Path runDir) {
        return Trigger.resumeId(runDir.toAbsolutePath().normalize().toString());
    }

    /** {@code prompts.json} in the run directory: which prompt (id, version, a hash of its text) each agent ran, for the run record. */
    private static void writePromptManifest(Path runDir, HarnessExecutor executor, LoomScript script) throws java.io.IOException {
        var catalog = executor.promptCatalog();
        if (catalog == null) return;
        var uses = catalog.usesOf(script);
        if (uses.isEmpty()) return;
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        for (var u : uses) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("agent", u.agent());
            row.put("prompt", u.reference());
            row.put("version", u.version());
            row.put("hash", u.hash());
            rows.add(row);
        }
        java.nio.file.Files.writeString(runDir.resolve("prompts.json"), new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(rows));
    }
}
