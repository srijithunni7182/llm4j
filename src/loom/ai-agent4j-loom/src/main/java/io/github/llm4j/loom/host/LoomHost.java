package io.github.llm4j.loom.host;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.channel.Answers;
import io.github.llm4j.loom.context.WindowLayers;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LiveRuns;
import io.github.llm4j.loom.execution.LoomRun;
import io.github.llm4j.loom.execution.StopOutcome;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.knowledge.EmbeddingFactory;
import io.github.llm4j.loom.memory.MemoryEngine;
import io.github.llm4j.loom.observe.ObserveProvider;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunStopped;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.loom.trigger.InMemoryTriggerStore;
import io.github.llm4j.loom.trigger.Schedules;
import io.github.llm4j.loom.trigger.Trigger;
import io.github.llm4j.loom.trigger.TriggerRunner;
import io.github.llm4j.loom.trigger.TriggerTarget;
import io.github.llm4j.secret.InvokerCredentials;
import io.github.llm4j.secret.MissingInvokerCredentialException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Runs the workflows of one script from an application: blocking or in the background, for a person or for nobody, and, when it has a
 * {@link Persistence}, pausing at a question, a timer or a limit and carrying on later, even after the process restarts. Each run gets its own
 * {@link HarnessExecutor}, assembled in one place from what the host was given; the finished steps of a resumed run are replayed from its journal, so
 * nothing paid for or sent is done twice.
 *
 * <pre>{@code
 * LoomHost host = LoomHost.builder(script, tools, models).persistence(Persistence.file(Path.of("var/loom"))).build();
 * RunOutcome out = host.run(RunRequest.of("Support", Map.of("ticket", text)));
 * if (out instanceof RunOutcome.Suspended s) {            // it asked a person; nothing is running
 *     host.answer(s.runId(), s.stepId(), "yes", "asha");  // the run carries on
 * }
 * }</pre>
 *
 * Thread-safe. A run id is worked on by one thread at a time.
 */
public final class LoomHost implements AutoCloseable {

    private static final Logger log = Logger.getLogger(LoomHost.class.getName());

    private final LoomScript script;
    private final ToolRegistry tools;
    private final LLMClientFactory models;
    private final Persistence persistence;
    private final Builder o;
    private final Clock clock;
    private final RunRegistry registry;
    private final AnswerBook answers;
    private final Executor runner;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Map<String, HarnessExecutor> live = new ConcurrentHashMap<>();
    private TriggerRunner triggerRunner;
    private volatile boolean closing;
    private final java.util.concurrent.atomic.AtomicInteger shutdowns = new java.util.concurrent.atomic.AtomicInteger();

    private LoomHost(Builder b) {
        this.script = b.script;
        this.tools = b.tools;
        this.models = b.models;
        this.persistence = b.persistence;
        this.o = b;
        this.clock = b.clock == null ? Clock.systemUTC() : b.clock;
        DurabilityGuard.check(script, persistence.durable(), b.allowNonDurable);
        this.registry = persistence.registry(b.scriptRef);
        this.answers = persistence.answers();
        this.runner = b.runner != null ? b.runner : task -> {
            Thread t = new Thread(task, "loom-host-run");
            t.setDaemon(true);
            t.start();
        };
    }

    public static Builder builder(LoomScript script, ToolRegistry tools, LLMClientFactory models) {
        return new Builder(script, tools, models);
    }

    /** Creates every table a {@link Persistence#jdbc(DataSource)} host uses. Safe to call on every start. */
    public static void createSchema(DataSource dataSource) {
        Persistence.createSchema(dataSource);
    }

    public LoomScript script() {
        return script;
    }

    public boolean durable() {
        return persistence.durable();
    }

    // ── running ────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs a workflow on the calling thread.
     *
     * @return how it ended; a run that asked a person or is waiting for a timer or a limit is {@link RunOutcome.Suspended}, not an error
     * @throws LoomRunFailedException if the run failed
     * @throws MissingInvokerCredentialException before anything ran, if the script needs a person's credentials the request does not supply
     */
    public RunOutcome run(RunRequest request) {
        String runId = idOf(request);
        claim(runId);
        try {
            RunRecord fresh = newRecord(runId, request.workflow(), request.inputs(), request.origin());
            return execute(runId, fresh, null, request.invoker(), label(request, runId));
        } catch (RuntimeException e) {
            release(runId);
            throw e;
        }
    }

    /** Starts a workflow on the host's executor and returns at once; the handle steers, pauses, cancels and awaits it. The executor is shut down when the run ends. */
    public LoomRun start(RunRequest request) {
        String runId = idOf(request);
        claim(runId);
        return launch(runId, newRecord(runId, request.workflow(), request.inputs(), request.origin()), null, request.invoker(), label(request, runId));
    }

    /** Carries on a suspended (or interrupted) run from its journal, on the calling thread. */
    public RunOutcome resume(String runId, InvokerCredentials invoker) {
        RunRecord previous = resumable(runId);
        claim(runId);
        try {
            return execute(runId, previous, previous, invoker, runId);
        } catch (RuntimeException e) {
            release(runId);
            throw e;
        }
    }

    public LoomRun resumeAsync(String runId, InvokerCredentials invoker) {
        RunRecord previous = resumable(runId);
        claim(runId);
        return launch(runId, previous, previous, invoker, runId);
    }

    private RunRecord resumable(String runId) {
        if (!persistence.durable()) throw new IllegalStateException("this host has no persistence, so a run cannot be resumed");
        RunRecord rec = registry.find(runId).orElseThrow(() -> new IllegalArgumentException("no run " + runId));
        if (rec.status() == RunRecord.Status.COMPLETED) throw new IllegalStateException("run " + runId + " has already completed");
        return rec;
    }

    private RunRecord newRecord(String runId, String workflow, Map<String, String> inputs, String origin) {
        Instant now = clock.instant();
        return new RunRecord(runId, workflow, inputs, origin, RunRecord.Status.RUNNING, null, null, null, null, null, now, now);
    }

    private RunOutcome execute(String runId, RunRecord record, RunRecord previous, InvokerCredentials invoker, String label) {
        HarnessExecutor ex = null;
        try {
            registry.save(record.with(RunRecord.Status.RUNNING, null, null, null, null, null, clock.instant()));
            ex = executorFor(runId);
            live.put(runId, ex);
            try (AutoCloseable registered = LiveRuns.shared().register(label, ex)) {
                ex.executeWorkflow(record.workflow(), record.inputs(), invoker);
            }
            registry.save(record.with(RunRecord.Status.COMPLETED, null, null, null, null, null, clock.instant()));
            return new RunOutcome.Completed(runId, Collections.unmodifiableMap(new LinkedHashMap<>(ex.getContext().getAll())));
        } catch (RunSuspended s) {
            if (!persistence.durable()) return failed(runId, record, previous, new IllegalStateException(
                    "run " + runId + " would suspend (" + s.reason() + ") but this host has no persistence, so it cannot resume: give the host a persistence"));
            registry.save(record.with(RunRecord.Status.SUSPENDED, s.reason(), s.resumeAt(), s.stepId(), s.prompt(), s.detail(), clock.instant()));
            return new RunOutcome.Suspended(runId, s.reason(), s.resumeAt(), s.stepId(), s.prompt());
        } catch (RunStopped s) {
            registry.save(record.with(RunRecord.Status.STOPPED, null, null, null, null, s.point(), clock.instant()));
            return new RunOutcome.Stopped(runId, s.point());
        } catch (MissingInvokerCredentialException m) {
            registry.save((previous != null ? previous : record).with(previous != null ? previous.status() : RunRecord.Status.FAILED,
                    previous == null ? null : previous.reason(), previous == null ? null : previous.resumeAt(), previous == null ? null : previous.stepId(),
                    previous == null ? null : previous.prompt(), "needs the person's credentials", clock.instant()));
            throw m;
        } catch (Throwable t) {
            return failed(runId, record, previous, t);
        } finally {
            if (ex != null) closeExecutor(ex);
            live.remove(runId);
            release(runId);
        }
    }

    private RunOutcome failed(String runId, RunRecord record, RunRecord previous, Throwable t) {
        registry.save(record.with(RunRecord.Status.FAILED, null, null, null, null, String.valueOf(t.getMessage()), clock.instant()));
        throw t instanceof LoomRunFailedException f ? f : new LoomRunFailedException(runId, t);
    }

    private LoomRun launch(String runId, RunRecord record, RunRecord previous, InvokerCredentials invoker, String label) {
        HarnessExecutor ex;
        try {
            registry.save(record.with(RunRecord.Status.RUNNING, null, null, null, null, null, clock.instant()));
            ex = executorFor(runId);
        } catch (MissingInvokerCredentialException m) {
            release(runId);
            throw m;
        } catch (RuntimeException e) {
            registry.save(record.with(RunRecord.Status.FAILED, null, null, null, null, String.valueOf(e.getMessage()), clock.instant()));
            release(runId);
            throw new LoomRunFailedException(runId, e);
        }
        live.put(runId, ex);
        try {
            return LoomRun.start(ex, label, record.workflow(), record.inputs(), record.origin(), invoker, runner, done -> finish(runId, record, previous, ex, done));
        } catch (RuntimeException rejected) {
            closeExecutor(ex);
            live.remove(runId);
            registry.save(record.with(RunRecord.Status.FAILED, null, null, null, null, "could not be started: " + rejected.getMessage(), clock.instant()));
            release(runId);
            throw rejected;
        }
    }

    private void finish(String runId, RunRecord record, RunRecord previous, HarnessExecutor ex, LoomRun done) {
        try {
            Throwable why = done.failure();
            Instant now = clock.instant();
            switch (done.status()) {
                case COMPLETED -> registry.save(record.with(RunRecord.Status.COMPLETED, null, null, null, null, null, now));
                case SUSPENDED -> {
                    RunSuspended s = (RunSuspended) why;
                    if (persistence.durable()) registry.save(record.with(RunRecord.Status.SUSPENDED, s.reason(), s.resumeAt(), s.stepId(), s.prompt(), s.detail(), now));
                    else registry.save(record.with(RunRecord.Status.FAILED, null, null, null, null, "would suspend but this host has no persistence", now));
                }
                case STOPPED -> registry.save(record.with(RunRecord.Status.STOPPED, null, null, null, null, ((RunStopped) why).point(), now));
                default -> {
                    if (why instanceof MissingInvokerCredentialException && previous != null) {
                        registry.save(previous.with(previous.status(), previous.reason(), previous.resumeAt(), previous.stepId(), previous.prompt(),
                                "needs the person's credentials", now));
                    } else {
                        registry.save(record.with(RunRecord.Status.FAILED, null, null, null, null, why == null ? "failed" : String.valueOf(why.getMessage()), now));
                    }
                }
            }
        } finally {
            closeExecutor(ex);
            live.remove(runId);
            release(runId);
        }
    }

    // ── people ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Gives a suspended run the answer to its question and lets it carry on: through the trigger runner when the host runs one, else at once on the host's
     * executor. Refused, with the reason, when the run is not waiting at that step or the question was answered.
     */
    public AnswerResult answer(String runId, String stepId, String text, String by) {
        if (!persistence.durable()) return new AnswerResult(false, "this host has no persistence, so no run is waiting");
        RunRecord rec = registry.find(runId).orElse(null);
        if (rec == null) return new AnswerResult(false, "no run " + runId);
        AnswerBook.Question q = answers.find(runId, stepId).orElse(null);
        if (rec.status() != RunRecord.Status.SUSPENDED && !(q != null && q.answered())) return new AnswerResult(false, "run " + runId + " is not suspended (" + rec.status() + ")");
        if (q == null) return new AnswerResult(false, "run " + runId + " is not waiting at " + stepId);
        if (q.answered()) return new AnswerResult(false, "already answered by " + q.by() + " at " + q.answeredAt());
        String clean = text == null ? "" : text.strip();
        String chosen = clean;
        if (!q.choices().isEmpty()) {
            chosen = Answers.match(q.choices(), clean);
            if (chosen == null) return new AnswerResult(false, "Not understood: this takes one of " + String.join(", ", q.choices()));
        } else if (clean.isEmpty()) {
            return new AnswerResult(false, "the answer is empty");
        }
        Instant now = clock.instant();
        if (!answers.answer(runId, stepId, chosen, by, now)) return new AnswerResult(false, "already answered");
        audit(runId, "answered", Map.of("run", runId, "step", stepId, "by", String.valueOf(by), "answer", chosen));
        registry.save(rec.with(RunRecord.Status.RESUMING, rec.reason(), rec.resumeAt(), rec.stepId(), rec.prompt(), rec.detail(), now));
        TriggerRunner running;
        synchronized (this) { running = triggerRunner; }
        if (running != null) {
            persistence.triggers().upsert(Trigger.resume(runId, now, "answered", 0));
        } else {
            try {
                resumeAsync(runId, null);
            } catch (MissingInvokerCredentialException e) {
                return new AnswerResult(true, "Recorded: " + chosen + ". The run needs the person's credentials to carry on; resume it with them.");
            }
        }
        return new AnswerResult(true, "Recorded: " + chosen);
    }

    public List<RunRecord> runs() {
        return registry.all();
    }

    public Optional<RunRecord> run(String runId) {
        return registry.find(runId);
    }

    // ── triggers ───────────────────────────────────────────────────────────────────────────────

    /** Fires triggers against this host: resume a run, start the script's scheduled workflow, or give an agent a task. */
    public TriggerTarget triggerTarget() {
        return this::fire;
    }

    private TriggerTarget.Outcome fire(Trigger trigger, String runId) throws Exception {
        if (trigger.target() instanceof Trigger.ResumeRun r) {
            if (active.contains(r.runId())) return new TriggerTarget.Outcome(TriggerTarget.Outcome.Status.SKIPPED_OVERLAP, null, r.runId() + " is already running");
            try {
                return outcome(resume(r.runId(), null));
            } catch (MissingInvokerCredentialException e) {
                return TriggerTarget.Outcome.human("needs the person's credentials");
            } catch (LoomRunFailedException e) {
                return TriggerTarget.Outcome.failed(e.getMessage());
            }
        }
        if (trigger.target() instanceof Trigger.StartWorkflow w) {
            if (o.scriptRef != null && !o.scriptRef.equals(w.script())) return TriggerTarget.Outcome.failed("not this host's script: " + w.script());
            try {
                return outcome(run(RunRequest.of(w.workflow(), w.args()).withRunId(runId).from("trigger")));
            } catch (MissingInvokerCredentialException e) {
                return TriggerTarget.Outcome.human("needs the person's credentials");
            } catch (LoomRunFailedException e) {
                return TriggerTarget.Outcome.failed(e.getMessage());
            }
        }
        Trigger.AgentTask a = (Trigger.AgentTask) trigger.target();
        HarnessExecutor ex = assemble(null, false);
        try {
            ex.runAgentTask(a.agent(), a.task());
            return TriggerTarget.Outcome.done();
        } finally {
            closeExecutor(ex);
        }
    }

    private static TriggerTarget.Outcome outcome(RunOutcome o) {
        if (o instanceof RunOutcome.Suspended s) {
            return s.reason() == RunSuspended.Reason.HUMAN || s.resumeAt() == null ? TriggerTarget.Outcome.human(s.prompt()) : TriggerTarget.Outcome.suspended(s.resumeAt(), "suspended: " + s.reason());
        }
        if (o instanceof RunOutcome.Stopped s) return TriggerTarget.Outcome.failed("stopped: " + s.detail());
        return TriggerTarget.Outcome.done();
    }

    /** Writes the script's {@code schedule} blocks to the trigger store (idempotent: an existing schedule keeps its next time). */
    public void registerSchedules() {
        requireDurable();
        if (!script.getSchedules().isEmpty()) Schedules.reconcile(script, o.scriptRef, persistence.triggers(), clock.instant());
    }

    /**
     * Registers the schedules and starts polling for due triggers. Several hosts on one store fire each trigger once.
     *
     * @param owner names this host to the store (default: the machine and process)
     */
    public synchronized void startTriggers(Duration poll, String owner, Duration staleAfter, Consumer<TriggerRunner.Fired> listener) {
        requireDurable();
        if (triggerRunner != null) return;
        registerSchedules();
        TriggerRunner r = owner == null ? new TriggerRunner(persistence.triggers(), triggerTarget(), clock) : new TriggerRunner(persistence.triggers(), triggerTarget(), clock, owner);
        if (staleAfter != null) r.staleAfter(staleAfter);
        if (listener != null) r.onFired(listener);
        r.start(poll);
        triggerRunner = r;
    }

    public synchronized void stopTriggers() {
        if (triggerRunner != null) {
            triggerRunner.stop();
            triggerRunner = null;
        }
    }

    /**
     * Checks that the script can run here, now, and says so before any run does: every problem in it (an unknown agent, a tool that cannot be created, a task that
     * is not registered, a model with no key) in one {@link io.github.llm4j.loom.execution.LoomLoadException}. Builds and discards an executor that has no journal and no
     * trigger store, so it writes nothing.
     */
    public void validate() {
        closeExecutor(assemble(null, false));
    }

    // ── executors ──────────────────────────────────────────────────────────────────────────────

    /** An executor for {@code runId}, assembled and initialized, with the journal and triggers of that run. The caller shuts it down. */
    public HarnessExecutor newExecutor(String runId) {
        return executorFor(runId);
    }

    private HarnessExecutor executorFor(String runId) {
        return assemble(runId, true);
    }

    private HarnessExecutor assemble(String runId, boolean forRun) {
        HarnessExecutor ex = new HarnessExecutor(script, tools, models);
        if (o.baseDir != null) ex.setBaseDir(o.baseDir);
        if (o.scriptRef != null) ex.setScriptRef(o.scriptRef);
        if (o.secretStore != null) ex.setSecretStore(o.secretStore);
        if (o.envLookup != null) ex.setEnvLookup(o.envLookup);
        ex.setClock(clock);
        if (o.sleeper != null) ex.setSleeper(o.sleeper);
        ex.setLenient(o.lenient);
        if (!o.toolReach.isEmpty()) ex.setToolReach(o.toolReach);
        if (o.taskRegistry != null) ex.setTaskRegistry(o.taskRegistry);
        if (o.promptRegistry != null) ex.setPromptRegistry(o.promptRegistry);
        if (o.priceTable != null) ex.setPriceTable(o.priceTable);
        if (o.maxTokens != null || o.maxCalls != null || o.maxCost != null) ex.setBudgetOverrides(o.maxTokens, o.maxCalls, o.maxCost);
        if (o.contextWindows != null) ex.setContextWindows(o.contextWindows);
        if (o.embeddingFactory != null) ex.setEmbeddingFactory(o.embeddingFactory);
        if (o.memoryEngine != null) ex.setMemoryEngine(o.memoryEngine);
        if (o.auditLogger != null) ex.setAuditLogger(o.auditLogger);
        HumanInterface human = o.humanInterface;
        if (persistence.durable()) {
            if (forRun) {
                ex.setJournal(persistence.journal(runId));
                ex.setRunId(runId);
                ex.setRunLocator(persistence.locator(runId));
                ex.setTriggerStore(persistence.triggers());
                if (human == null) human = new HostHumanInterface(runId, answers, o.listeners, clock);
            } else {
                ex.setTriggerStore(new InMemoryTriggerStore()); // a task given to an agent: the script's schedules are already stored
            }
            if (!script.getDecisions().isEmpty() || !script.getLadders().isEmpty()) ex.setAutonomy(persistence.ledger(), persistence.levels());
        } else if (human == null) {
            human = new HumanInterface() {
                @Override
                public String promptHuman(String message) {
                    throw new IllegalStateException("the run asked a person (\"" + message + "\") but this host has no persistence, so it cannot wait and resume: give the host a persistence");
                }
            };
        }
        if (human != null) ex.setHumanInterface(human);
        if (o.observe != null) {
            if (o.scriptFile != null) o.observe.attach(ex.observable(), o.scriptFile);
            else o.observe.attach(ex.observable());
        }
        if (o.customizer != null) o.customizer.accept(ex);
        ex.initialize();
        return ex;
    }

    // ── shutdown ───────────────────────────────────────────────────────────────────────────────

    /**
     * Asks every run in progress to wrap up, waits up to {@code grace}, then suspends what is left (a run with a store resumes later) or cancels it. Returns
     * the run ids that were still going when the grace ran out.
     */
    public List<String> shutdown(Duration grace) {
        closing = true;
        stopTriggers();
        for (HarnessExecutor ex : live.values()) ex.wrapUp("*", "the application is shutting down", "host");
        long deadline = System.nanoTime() + grace.toNanos();
        while (!live.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        List<String> left = new ArrayList<>(live.keySet());
        for (String id : left) {
            HarnessExecutor ex = live.get(id);
            if (ex == null) continue;
            log.info("run " + id + " was still going after " + grace + ": " + (persistence.durable() ? "suspending it" : "cancelling it"));
            ex.cancel("*", "the application is shutting down", "host", persistence.durable() ? StopOutcome.SUSPEND : StopOutcome.DISCARD);
        }
        return left;
    }

    @Override
    public void close() {
        stopTriggers();
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private String idOf(RunRequest r) {
        return r.runId() != null ? r.runId() : UUID.randomUUID().toString();
    }

    private String label(RunRequest r, String runId) {
        return r.label() != null ? r.label() : r.workflow() + "#" + runId;
    }

    private void closeExecutor(HarnessExecutor ex) {
        try {
            ex.shutdown();
        } finally {
            shutdowns.incrementAndGet();
        }
    }

    /** How many executors this host has shut down: one for each it built for a run. Lets a test prove none is leaked. */
    int shutdownCount() {
        return shutdowns.get();
    }

    private void claim(String runId) {
        if (closing) throw new IllegalStateException("the host is shutting down");
        if (!active.add(runId)) throw new IllegalStateException("run " + runId + " is already running");
    }

    private void release(String runId) {
        active.remove(runId);
    }

    private void requireDurable() {
        if (!persistence.durable()) throw new IllegalStateException("this host has no persistence");
    }

    private void audit(String runId, String event, Map<String, Object> data) {
        if (o.auditLogger != null) o.auditLogger.logConversationEvent(runId, null, event, data);
    }

    // ── builder ────────────────────────────────────────────────────────────────────────────────

    public static final class Builder {
        private final LoomScript script;
        private final ToolRegistry tools;
        private final LLMClientFactory models;
        private Persistence persistence = Persistence.none();
        private Path baseDir;
        private String scriptRef;
        private Path scriptFile;
        private io.github.llm4j.secret.SecretStore secretStore;
        private Function<String, String> envLookup;
        private Clock clock;
        private io.github.llm4j.ratelimit.Sleeper sleeper;
        private boolean lenient;
        private Map<String, String> toolReach = Map.of();
        private io.github.llm4j.agent.task.TaskRegistry taskRegistry;
        private io.github.llm4j.agent.prompt.PromptRegistry promptRegistry;
        private io.github.llm4j.budget.PriceTable priceTable;
        private Long maxTokens;
        private Long maxCalls;
        private BigDecimal maxCost;
        private WindowLayers contextWindows;
        private EmbeddingFactory embeddingFactory;
        private MemoryEngine memoryEngine;
        private io.github.llm4j.audit.AuditLogger auditLogger;
        private HumanInterface humanInterface;
        private final List<QuestionListener> listeners = new ArrayList<>();
        private ObserveProvider.Session observe;
        private Consumer<HarnessExecutor> customizer;
        private Executor runner;
        private boolean allowNonDurable;

        private Builder(LoomScript script, ToolRegistry tools, LLMClientFactory models) {
            this.script = java.util.Objects.requireNonNull(script, "script");
            this.tools = java.util.Objects.requireNonNull(tools, "tools");
            this.models = java.util.Objects.requireNonNull(models, "models");
        }

        public Builder persistence(Persistence p) { this.persistence = java.util.Objects.requireNonNull(p); return this; }
        /** Where relative paths in the script (prompts, knowledge, file tools) start. */
        public Builder baseDir(Path dir) { this.baseDir = dir; return this; }
        /** How triggers and the journal name this script; for a script on disk, its path. */
        public Builder scriptRef(String ref) { this.scriptRef = ref; return this; }
        /** The script's file, when it has one, so a rules file kept beside it is found by the live page. */
        public Builder scriptFile(Path file) { this.scriptFile = file; return this; }
        public Builder secretStore(io.github.llm4j.secret.SecretStore store) { this.secretStore = store; return this; }
        public Builder envLookup(Function<String, String> lookup) { this.envLookup = lookup; return this; }
        public Builder clock(Clock clock) { this.clock = clock; return this; }
        public Builder sleeper(io.github.llm4j.ratelimit.Sleeper sleeper) { this.sleeper = sleeper; return this; }
        public Builder lenient(boolean lenient) { this.lenient = lenient; return this; }
        /** What your own tools reach ({@code Name.reach = reads} in a {@code .loot} file), for the audit and the trace. */
        public Builder toolReach(Map<String, String> reach) { this.toolReach = reach == null ? Map.of() : Map.copyOf(reach); return this; }
        public Builder taskRegistry(io.github.llm4j.agent.task.TaskRegistry tasks) { this.taskRegistry = tasks; return this; }
        public Builder promptRegistry(io.github.llm4j.agent.prompt.PromptRegistry prompts) { this.promptRegistry = prompts; return this; }
        public Builder priceTable(io.github.llm4j.budget.PriceTable prices) { this.priceTable = prices; return this; }
        public Builder budget(Long tokens, Long calls, BigDecimal cost) { this.maxTokens = tokens; this.maxCalls = calls; this.maxCost = cost; return this; }
        public Builder contextWindows(WindowLayers windows) { this.contextWindows = windows; return this; }
        public Builder embeddingFactory(EmbeddingFactory factory) { this.embeddingFactory = factory; return this; }
        public Builder memoryEngine(MemoryEngine engine) { this.memoryEngine = engine; return this; }
        public Builder auditLogger(io.github.llm4j.audit.AuditLogger logger) { this.auditLogger = logger; return this; }
        /** Your own way of asking people. Without one, a host with a persistence suspends runs at a question ({@link #questionListener}). */
        public Builder humanInterface(HumanInterface human) { this.humanInterface = human; return this; }
        public Builder questionListener(QuestionListener listener) { this.listeners.add(listener); return this; }
        /** A live-page session; each executor is attached before it is initialized. */
        public Builder observe(ObserveProvider.Session session) { this.observe = session; return this; }
        /** Runs on every executor after the host's own setup and before {@code initialize()}. */
        public Builder customizer(Consumer<HarnessExecutor> customizer) { this.customizer = customizer; return this; }
        /** Where {@link LoomHost#start} and {@link LoomHost#resumeAsync} run workflows (default: a daemon thread for each). */
        public Builder runner(Executor runner) { this.runner = runner; return this; }
        /** Accept a script that can wait when there is no persistence; such a run then fails instead of suspending. Never accepts a script that needs a store. */
        public Builder allowNonDurable(boolean allow) { this.allowNonDurable = allow; return this; }

        public LoomHost build() {
            return new LoomHost(this);
        }
    }
}
