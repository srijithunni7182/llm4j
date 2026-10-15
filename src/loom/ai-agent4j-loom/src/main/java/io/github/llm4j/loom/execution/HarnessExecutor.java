package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.agent.persona.PersonaLibrary;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.audit.AuditEvent;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.audit.FileAuditLogger;
import io.github.llm4j.audit.NoOpAuditLogger;
import io.github.llm4j.agent.memory.SemanticMemoryService;
import io.github.llm4j.agent.rag.RAGAgent;
import io.github.llm4j.agent.schedule.AgentScheduler;
import io.github.llm4j.agent.skill.AgentSkill;
import io.github.llm4j.agent.skill.FileSystemSkillLoader;
import io.github.llm4j.agent.skill.SkillLoader;
import io.github.llm4j.loom.ast.*;
import io.github.llm4j.loom.runtime.*;
import io.github.llm4j.loom.memory.MemoryEngine;
import io.github.llm4j.loom.memory.TranscriptAccumulationEngine;
import io.github.llm4j.mcp.McpClient;
import io.github.llm4j.mcp.McpToolAdapter;
import io.github.llm4j.mcp.StdioMcpTransport;
import io.github.llm4j.privacy.PIIDetector;
import io.github.llm4j.secret.InvokerCredentials;
import io.github.llm4j.privacy.RegexPIIDetector;
import io.github.llm4j.routing.CostAwareRoutingStrategy;
import io.github.llm4j.routing.ProviderTier;
import io.github.llm4j.routing.RoutingLLMClient;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class HarnessExecutor implements LoomEngine {
    private static final Logger log = Logger.getLogger(HarnessExecutor.class.getName());

    private final LoomScript script;
    private final ToolRegistry toolRegistry;
    private final LLMClientFactory llmClientFactory;
    private final Map<String, ReActAgent> activeAgents = new HashMap<>();
    private final Map<String, McpClient> mcpClients   = new HashMap<>();
    private final Map<String, RAGAgent> ragAgents     = new HashMap<>();
    private final Map<String, SemanticMemoryService> memoryServices = new HashMap<>();
    private final Map<String, AgentScheduler> schedulers = new HashMap<>();
    private final PIIDetector piiDetector = new RegexPIIDetector();
    private final VariableContext context;
    private HumanInterface humanInterface;
    private AuditLogger auditLogger = new NoOpAuditLogger();
    private io.github.llm4j.loom.prompt.PromptCatalog promptCatalog;
    private final String sessionId = UUID.randomUUID().toString();
    private MemoryEngine memoryEngine = new TranscriptAccumulationEngine();
    private io.github.llm4j.loom.context.WindowLayers contextWindows;
    private io.github.llm4j.loom.context.CompactorRegistry compactors;

    // ── Durable runs ─────────────────────────────────────────────────────
    private RunJournal journal = RunJournal.inMemory();
    private Generations generations;
    private RunJournal generationsOf;
    private final Rewinder rewinder = new Rewinder(this);
    private final ThreadLocal<java.util.ArrayDeque<Rewinder.Frame>> frames = ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private final ThreadLocal<Boolean> rootNext = ThreadLocal.withInitial(() -> false);
    /** True when the script has a checkpoint or a rewind: only then does the run keep what it needs to go back (the question behind each answer). */
    private boolean rewindsUsed;
    private boolean simulate;
    private boolean simulateTasks;
    /** The id of the step this thread is executing: its position in the script (stable across runs). */
    private final ThreadScope threadScope = new ThreadScope();
    /** True while an outermost workflow is running on this executor. */
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    private final ThreadLocal<String> step = threadScope.step;
    /** Counts delegates started on this thread, so a retried delegate's tool calls are numbered afresh. */
    private final java.util.concurrent.atomic.AtomicLong delegateAttempts = new java.util.concurrent.atomic.AtomicLong();
    private final ThreadLocal<Long> attempt = ThreadLocal.withInitial(() -> 0L);
    /**
     * Threads for parallel branches and step timeouts. Model calls wait on the network, so branches
     * must not be limited by CPU count (the common pool has one worker on a 2-CPU container).
     */
    private static final java.util.concurrent.atomic.AtomicInteger BRANCH_IDS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.ExecutorService BRANCHES = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "loom-branch-" + BRANCH_IDS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private final ThreadLocal<Map<String, Object>> locals = threadScope.locals;

    // ── Budgets ──────────────────────────────────────────────────────────
    private final ThreadLocal<List<io.github.llm4j.budget.Budget>> scopes = threadScope.budgets;
    private final LimitPolicy limits = new LimitPolicy(new LimitPolicy.Host() {
        @Override public LoomScript script() { return script; }
        @Override public RunJournal journal() { return journal; }
        @Override public java.time.Clock clock() { return clock; }
        @Override public io.github.llm4j.ratelimit.Sleeper sleeper() { return sleeper; }
        @Override public String stepId() { return step.get(); }
        @Override public AuditLogger auditLogger() { return auditLogger; }
        @Override public String sessionId() { return sessionId; }
        @Override public boolean refills(io.github.llm4j.budget.BudgetExceeded be) { return budgets.refills(be); }
    });
    private final AgentPrompts prompts = new AgentPrompts(new AgentPrompts.Host() {
        @Override public LoomScript script() { return script; }
        @Override public io.github.llm4j.loom.prompt.PromptCatalog promptCatalog() { return promptCatalog; }
        @Override public Path baseDir() { return baseDir; }
    });
    private final PayloadText payloads = new PayloadText(new PayloadText.Host() {
        @Override public VariableContext view() { return HarnessExecutor.this.view(); }
        @Override public VariableContext context() { return context; }
    });

    /** Fills {@code {name}} and {@code {var.field}} placeholders in text the script wrote. */
    private String resolvePayload(String rawPayload) {
        return payloads.resolve(rawPayload);
    }

    private final InterventionCoordinator interventions = new InterventionCoordinator(new InterventionCoordinator.Host() {
        @Override public RunJournal journal() { return journal; }
        @Override public void trace(String type, String agent, String text, Map<String, Object> data) { HarnessExecutor.this.trace(type, agent, text, data); }
        @Override public void audit(String event, Map<String, Object> data) { HarnessExecutor.this.audit(event, data); }
        @Override public java.time.Instant now() { return clock.instant(); }
        @Override public AgentGuard guard(String agent) { return guards.get(agent); }
        @Override public boolean steerable(String agent) { return script.getAgents().stream().anyMatch(a -> a.getName().equals(agent) && a.isSteerable()); }
    });

    /** The agents running now, and the way to steer, pause or cancel them. */
    InterventionCoordinator interventions() {
        return interventions;
    }

    // ---- reaching a running agent (for an embedding application, a channel, or a rule) ----

    /** Sends a message to the running agent(s) named by {@code target} (a step id, an agent name, or {@code *}); returns how many took it. See {@link InterventionCoordinator#steer}. */
    public int steer(String target, String text, String origin) {
        return interventions.steer(target, text, origin);
    }

    /** Cancels the running agent(s); {@code outcome} is what the run does next, or null for the defaults. Returns how many were asked to stop. */
    public int cancel(String target, String reason, String origin, StopOutcome outcome) {
        return interventions.cancel(target, reason, origin, outcome);
    }

    /** Asks the running agent(s) to finish now with what they have (one last model call, tools off). */
    public int wrapUp(String target, String reason, String origin) {
        return interventions.wrapUp(target, reason, origin);
    }

    public int pauseAgents(String target, String origin) {
        return interventions.pause(target, origin);
    }

    public int resumeAgents(String target, String origin) {
        return interventions.resume(target, origin);
    }

    /** The steps with an agent running now, as {@code stepId -> agent name}. */
    public Map<String, String> runningAgents() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        interventions.running().forEach(a -> out.put(a.stepId(), a.agent()));
        return out;
    }

    private final BudgetCoordinator budgets = new BudgetCoordinator(scopes, new BudgetCoordinator.Host() {
        @Override public LoomScript script() { return script; }
        @Override public java.time.Clock clock() { return clock; }
        @Override public RunJournal journal() { return journal; }
        @Override public String stepId() { return step.get(); }
        @Override public List<String> modelsOf(AgentDef agent) { return HarnessExecutor.this.modelsOf(agent); }
        @Override public AuditLogger auditLogger() { return auditLogger; }
        @Override public String sessionId() { return sessionId; }
        @Override public HumanInterface humanInterface() { return humanInterface; }
    });
    private final DelegateRunner delegates = new DelegateRunner(budgets, limits, interventions, new DelegateRunner.Host() {
        @Override public io.github.llm4j.loom.ast.InterventionDef intervention() { return script.getIntervention(); }
        @Override public void beginAttempt() { attempt.set(delegateAttempts.incrementAndGet()); }
        @Override public String resolveName(String name) { return HarnessExecutor.this.resolveName(name); }
        @Override public AgentDef agentDef(String name) {
            return script.getAgents().stream().filter(a -> a.getName().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + name));
        }
        @Override public ReActAgent activeAgent(String name) { return activeAgents.get(name); }
        @Override public String stepId() { return step.get(); }
        @Override public RunJournal journal() { return journal; }
        @Override public VariableContext context() { return context; }
        @Override public VariableContext view() { return HarnessExecutor.this.view(); }
        @Override public String resolvePayload(String raw) { return HarnessExecutor.this.resolvePayload(raw); }
        @Override public Path baseDir() { return baseDir; }
        @Override public AgentVoice voice(String agent) { return voices.get(agent); }
        @Override public AgentMemory memory(String agent) { return memories.get(agent); }
        @Override public AgentGuard guard(String agent) { return guards.get(agent); }
        @Override public io.github.llm4j.loom.knowledge.Retriever retriever(String agent) { return retrievers.get(agent); }
        @Override public Map<String, String> sessions() { return sessions; }
        @Override public MemoryEngine memoryEngine() { return memoryEngine; }
        @Override public AuditLogger auditLogger() { return auditLogger; }
        @Override public String sessionId() { return sessionId; }
        @Override public HumanInterface humanInterface() { return humanInterface; }
        @Override public java.util.Optional<io.github.llm4j.loom.prompt.PromptUse> promptUsed(AgentDef agent) { return HarnessExecutor.this.promptUsed(agent); }
        @Override public ThreadLocal<List<io.github.llm4j.budget.Budget>> scopes() { return scopes; }
        @Override public void trace(String type, String agent, String text, Map<String, Object> data) { HarnessExecutor.this.trace(type, agent, text, data); }
        @Override public void runFailureHandler(List<Statement> onFailure, String message) { HarnessExecutor.this.runFailureHandler(onFailure, message); }
        @Override public void traceFailure(String kind, String name, String message, Throwable cause, io.github.llm4j.telemetry.ErrorCategory given, int attempts, boolean handled) {
            HarnessExecutor.this.traceFailure(kind, name, message, cause, given, attempts, handled);
        }
        @Override public ThreadScope.Snapshot captureScope() { return threadScope.capture(); }
        @Override public java.util.concurrent.ExecutorService branches() { return BRANCHES; }
        @Override public ReActAgent agentForDelegate(DelegateStmt stmt, AgentDef agentDef, ReActAgent agent) { return HarnessExecutor.this.agentForDelegate(stmt, agentDef, agent); }
        @Override public String beforeDelegateExecution(DelegateStmt stmt, AgentDef agentDef, String payload, String assembled) { return HarnessExecutor.this.beforeDelegateExecution(stmt, agentDef, payload, assembled); }
        @Override public void afterDelegateExecution(DelegateStmt stmt, AgentDef agentDef, String payload, String effective, io.github.llm4j.agent.AgentResult result, Object finalValue, DelegateSuccessHandler onSuccess) {
            HarnessExecutor.this.afterDelegateExecution(stmt, agentDef, payload, effective, result, finalValue, onSuccess);
        }
        @Override public void onDelegateError(DelegateStmt stmt, AgentDef agentDef, String payload, String effective, Exception error, int attemptNumber, int maxAttempts) {
            HarnessExecutor.this.onDelegateError(stmt, agentDef, payload, effective, error, attemptNumber, maxAttempts);
        }
        @Override public void onDelegateReplayed(DelegateStmt stmt, AgentDef agentDef, Object value) { HarnessExecutor.this.onDelegateReplayed(stmt, agentDef, value); }
    });

    // ── Pausing and resuming ─────────────────────────────────────────────
    /** Journal key of the run's latest pause for a limit. */
    public static final String SUSPENSION = "#suspension";
    /** A sleep this short is waited out in place even with a durable journal: pausing the run costs more than it saves. */
    static final Duration INLINE_SLEEP = Duration.ofSeconds(30);
    private java.time.Clock clock = java.time.Clock.systemUTC();
    private io.github.llm4j.ratelimit.Sleeper sleeper = io.github.llm4j.ratelimit.Sleeper.SYSTEM;
    private int resumes;
    /** True when this top-level run began by resuming a pause for a limit or a sleep (not counted in {@link #resumes} for a sleep). */
    private boolean resumedFromPause;
    private io.github.llm4j.loom.trigger.TriggerStore triggerStore;
    private String runId;
    private String scriptRef = "script";
    private boolean lenient;
    private final io.github.llm4j.loom.tools.ToolFactory toolFactory = new io.github.llm4j.loom.tools.ToolFactory();
    private Path baseDir = Path.of("").toAbsolutePath();
    private io.github.llm4j.loom.knowledge.EmbeddingFactory embeddingFactory;
    private final Map<String, io.github.llm4j.loom.knowledge.KnowledgeIndex> knowledge = new java.util.LinkedHashMap<>();
    private final Map<String, io.github.llm4j.loom.knowledge.Retriever> retrievers = new HashMap<>();
    private final Map<String, AgentMemory> memories = new HashMap<>();
    private final Map<String, AgentGuard> guards = new HashMap<>();
    private final Map<String, AgentVoice> voices = new HashMap<>();
    /** Which memory session each running step belongs to (steps may run on other threads). */
    private final Map<String, String> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.function.Function<String, String> envLookup = System::getenv;
    private io.github.llm4j.secret.SecretStore secretStore;
    /** What {@code env.NAME} and {@code secret.NAME} resolve through: see {@link #credential(String)}. */
    private final java.util.function.Function<String, String> credentials = this::credential;
    private final List<java.util.function.Consumer<RunSuspended>> suspensionListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    
    @FunctionalInterface
    public interface DelegateSuccessHandler {
        void onSuccess(io.github.llm4j.agent.AgentResult result, Object finalValue);
    }

    public HarnessExecutor(LoomScript script, ToolRegistry toolRegistry, LLMClientFactory llmClientFactory) {
        this.script = script;
        this.toolRegistry = toolRegistry;
        this.llmClientFactory = llmClientFactory;
        this.context = new DefaultVariableContext();
        this.rewindsUsed = io.github.llm4j.loom.ast.StatementWalker.any(script, st -> st instanceof CheckpointStmt || st instanceof RewindStmt);
    }

    private io.github.llm4j.agent.task.TaskRegistry taskRegistry;
    private final TaskRunner taskRunner = new TaskRunner(this);

    /**
     * The tasks {@code run} statements can use. Default: every task the class path declares as a service
     * ({@link io.github.llm4j.agent.task.TaskRegistry#discovered()}). Tasks are operator-supplied; a script can name one, never load one.
     */
    public void setTaskRegistry(io.github.llm4j.agent.task.TaskRegistry registry) { this.taskRegistry = registry; }

    private java.util.Map<String, String> toolReach = java.util.Map.of();
    private final java.util.Map<String, io.github.llm4j.loom.security.ToolReach> reachCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** What the {@code .loot} file declares your own tools reach ({@code Name.reach = reads}); without it a tool the script does not define is assumed to reach everything. */
    public void setToolReach(java.util.Map<String, String> declared) {
        this.toolReach = declared == null ? java.util.Map.of() : java.util.Map.copyOf(declared);
        reachCache.clear();
    }

    /** What the tool reaches, as the static audit would say: the same code answers both. */
    io.github.llm4j.loom.security.ToolReach reachOf(String tool) {
        return reachCache.computeIfAbsent(tool, name -> {
            // tools Loom itself adds to an agent reach only the agent's own data: the audit counts them as private data, and so does the trace
            boolean ownData = "save_memory_fact".equals(name) || script.getKnowledgeBases().stream()
                    .anyMatch(kb -> name.equals("search_" + kb.getName().toLowerCase(java.util.Locale.ROOT)));
            if (ownData) return new io.github.llm4j.loom.security.ToolReach(false, true, false, true);
            java.util.Map<String, io.github.llm4j.loom.ast.ToolDef> declared = new java.util.HashMap<>();
            for (io.github.llm4j.loom.ast.ToolDef t : script.getTools()) declared.put(t.getName(), t);
            return io.github.llm4j.loom.security.ToolReach.resolve(name, declared, toolReach);
        });
    }

    public synchronized io.github.llm4j.agent.task.TaskRegistry getTaskRegistry() {
        if (taskRegistry == null) taskRegistry = io.github.llm4j.agent.task.TaskRegistry.discovered();
        return taskRegistry;
    }

    public void setHumanInterface(HumanInterface humanInterface) { this.humanInterface = humanInterface; }
    /** Supplies prompts for {@code prompt:} and {@code system_template:} from any registry (a host's own, or a folder of markdown files). */
    public void setPromptRegistry(PromptRegistry promptRegistry) {
        this.promptCatalog = promptRegistry == null ? null : new io.github.llm4j.loom.prompt.PromptCatalog(promptRegistry, java.util.Map.of());
    }

    /** Supplies prompts with versions pinned for this run (see {@code weave --prompt}). */
    public void setPromptCatalog(io.github.llm4j.loom.prompt.PromptCatalog promptCatalog) { this.promptCatalog = promptCatalog; }

    public io.github.llm4j.loom.prompt.PromptCatalog promptCatalog() { return promptCatalog; }
    public void setAuditLogger(AuditLogger auditLogger) {
        this.auditLogger = new HoldingAuditLogger(auditLogger != null ? auditLogger : new NoOpAuditLogger(), decider);
    }
    public void setMemoryEngine(MemoryEngine memoryEngine) {
        this.memoryEngine = memoryEngine != null ? memoryEngine : new TranscriptAccumulationEngine();
    }
    protected MemoryEngine getMemoryEngine() {
        return memoryEngine;
    }

    /**
     * Where step results are recorded. Give the same journal to a new executor (on any machine) and
     * {@code executeWorkflow} replays the recorded steps and continues from the first unrecorded one.
     */
    public void setJournal(RunJournal journal) {
        this.journal = journal != null ? journal : RunJournal.inMemory();
    }

    public RunJournal getJournal() {
        return journal;
    }

    HumanInterface humanInterface() {
        return humanInterface;
    }

    String maskPii(String text) {
        return piiDetector.mask(text, io.github.llm4j.privacy.MaskingStrategy.PLACEHOLDER);
    }

    void audit(String event, Map<String, Object> data) {
        auditLogger.logConversationEvent(sessionId, null, event, data);
    }

    // ---- what Rewinder needs ------------------------------------------------------------------------------

    RunJournal journal() { return journal; }
    io.github.llm4j.loom.memory.MemoryEngine memory() { return memoryEngine; }
    java.time.Instant now() { return clock.instant(); }
    void setVariable(String name, Object value) { context.setVariable(name, value); }
    void removeVariable(String name) { context.removeVariable(name); }
    Map<String, Object> variables() { return context.getAll(); }
    String resolve(String text) { return resolvePayload(text); }
    boolean rewindsUsed() { return rewindsUsed; }
    boolean simulating() { return simulate; }

    /** True when tasks that change things are described and not run: a simulated run, or a mock evaluation. */
    boolean simulatingTasks() { return simulate || simulateTasks; }
    /** True when a budget has refused a call or has nothing left: a rewind would only spend what isn't there. */
    boolean overBudget() { return budgets.overBudget(); }
    // ---- what the Decider needs ---------------------------------------------------------------------------------------

    LoomScript script() { return script; }
    Path baseDir() { return baseDir; }
    private final Decider decider = new Decider(this);
    private io.github.llm4j.loom.autonomy.Ledger ledger;
    private io.github.llm4j.loom.autonomy.LevelStore levelStore;
    private Replay replay;
    private String runLocator;

    /** Where the ledger and the levels of decisions are kept; without them a decision runs at its start level and writes nothing. */
    public void setAutonomy(io.github.llm4j.loom.autonomy.Ledger ledger, io.github.llm4j.loom.autonomy.LevelStore levels) {
        this.ledger = ledger;
        this.levelStore = levels;
    }

    io.github.llm4j.loom.autonomy.Ledger ledger() { return ledger; }

    private final Map<String, String> servedModels = new java.util.concurrent.ConcurrentHashMap<>();

    /** The model the provider said answered the agent's latest call, or null when it said nothing (or no call has been made). */
    String servedModel(String agent) { return servedModels.get(agent); }

    void forgetServedModel(String agent) { servedModels.remove(agent); }
    io.github.llm4j.loom.autonomy.LevelStore levelStore() { return levelStore; }
    Replay replay() { return replay; }

    /** Where this run's journal can be found by someone else later (its run directory); a replay of a case opens the run from it. Defaults to the run id. */
    public void setRunLocator(String locator) { this.runLocator = locator; }

    String runLocator() { return runLocator != null ? runLocator : runId; }

    /** Makes this run a replay of one case under a candidate: nobody is asked, nothing is written, reads come from what the case recorded. */
    public void setReplay(Replay replay) { this.replay = replay; }

    /** The reserved paths of the ledger and the levels, which tools that touch files must stay out of. */
    java.util.Set<Path> autonomyPaths() {
        java.util.Set<Path> out = new java.util.HashSet<>();
        if (ledger instanceof io.github.llm4j.loom.autonomy.FileLedger f) out.add(f.dir().toAbsolutePath().normalize());
        if (levelStore instanceof io.github.llm4j.loom.autonomy.FileLevelStore f) out.add(f.dir().toAbsolutePath().normalize());
        return out;
    }

    /** Runs something as though it were at another step (a decision's proposal or question is its own journaled step under the decide statement). */
    void atStep(String stepId, Runnable work) {
        String before = step.get();
        step.set(stepId);
        try {
            work.run();
        } finally {
            step.set(before);
        }
    }

    <T> T atStep(String stepId, java.util.function.Supplier<T> work) {
        String before = step.get();
        step.set(stepId);
        try {
            return work.get();
        } finally {
            step.set(before);
        }
    }

    /** Runs one delegate at the given step id: its result is journaled there and a resumed run reuses it. */
    void delegateAt(String stepId, DelegateStmt del) {
        atStep(stepId, () -> delegates.execute(del));
    }

    Decider decider() { return decider; }
    String envValue(String name) {
        try {
            return credentials.apply(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The level an agent that replaced another may start at: the replay of the old agent's cases under the new one (see {@link Decider}). */
    Inherited inheritedLevel(io.github.llm4j.loom.ast.DecisionDef def, String scope, io.github.llm4j.loom.autonomy.LevelState old, String identity) {
        return inheritance != null ? inheritance.inherited(def, scope, old, identity) : new Inherited(def.getStartAt(), "no replay is available, so it starts over");
    }

    /** The level a new agent inherits and why. */
    public record Inherited(io.github.llm4j.loom.autonomy.Level level, String reason, String rung) {
        public Inherited(io.github.llm4j.loom.autonomy.Level level, String reason) {
            this(level, reason, level == null ? null : level.word());
        }
    }

    /** What runs the replay when an agent changes with {@code test it on past cases}; without it a new agent starts over. */
    public interface Inheritance {
        Inherited inherited(io.github.llm4j.loom.ast.DecisionDef def, String scope, io.github.llm4j.loom.autonomy.LevelState old, String identity);

        /** The rung (by name) a new agent on a ladder starts on, given the rung and epoch the old agent had; the first rung when nothing says otherwise. */
        default Inherited inheritedRung(io.github.llm4j.loom.ast.DecisionDef def, String scope, String oldRung, int oldEpoch, String identity) {
            return new Inherited(null, "no replay is available, so it starts over", null);
        }
    }

    private Inheritance inheritance;

    public void setInheritance(Inheritance inheritance) { this.inheritance = inheritance; }

    /** The rung a new agent on a ladder may start on, from the replay of the old agent's cases under it (see {@link Inheritance#inheritedRung}). */
    Inherited inheritedRung(io.github.llm4j.loom.ast.DecisionDef def, String scope, String oldRung, int oldEpoch, String identity) {
        return inheritance != null ? inheritance.inheritedRung(def, scope, oldRung, oldEpoch, identity) : new Inherited(null, "no replay is available, so it starts over", null);
    }

    String recordedAnswer(String question) { return rewinder.recordedAnswer(question); }
    void recordAnswer(String question, String answer) { rewinder.recordAnswer(question, answer); }

    /** A tool by its declared name, as an agent would be given it (journaled effects and all). */
    Tool toolNamed(String name) { return resolveTool(name); }

    String forStorage(String agentName, String text) {
        AgentGuard guard = guards.get(agentName);
        return guard == null ? text : guard.forStorage(text);
    }

    private final RunTracing tracing = new RunTracing(new RunTracing.Host() {
        @Override public String stepId() { return step.get(); }
        @Override public java.time.Instant now() { return clock.instant(); }
        @Override public boolean hold(Runnable deliver) { return decider.hold(deliver); }
        @Override public io.github.llm4j.loom.security.ToolReach reachOf(String tool) { return HarnessExecutor.this.reachOf(tool); }
    });

    private String stopAt;
    private final RunControl control = new RunControl();

    {
        control.addListener(tracing::traceControl);
    }

    /** What an observer may do to this run: pause, resume, suspend, stop. Takes effect between steps. */
    public RunControl control() { return control; }
    /** Ends the run cleanly, as {@link io.github.llm4j.loom.runtime.RunStopped}, once the named step or checkpoint has completed. */
    public void setStopAt(String point) { this.stopAt = point; }
    /** A place a run can be told to stop at: a step id (with or without its attempt) or a checkpoint's name. */
    void stopPoint(String point) {
        if (stopAt != null && (stopAt.equals(point) || stopAt.equals(Generations.strip(point)))) throw new io.github.llm4j.loom.runtime.RunStopped(point);
    }
    public void setSimulate(boolean simulate) { this.simulate = simulate; }

    /**
     * Describe, and do not run, the tasks that change things ({@code TaskEffect.CHANGES}), while tools and models behave as they are given. A mock
     * evaluation uses this, so a free run cannot save a file, send a message or pay.
     */
    public void setSimulateTasks(boolean simulateTasks) { this.simulateTasks = simulateTasks; }
    public void setMaxRewinds(int max) { rewinder.setMaxRewinds(max); }
    void runHandler(List<Statement> handler, String key) { runBlock(handler, key); }

    /** The attempts of this run, read from the journal's boundary list (read again when the journal is replaced). */
    synchronized Generations generations() {
        if (generations == null || generationsOf != journal) {
            generations = new Generations(journal);
            generationsOf = journal;
        }
        return generations;
    }

    /** The current step as an effect or a person's answer identifies it: the same place in the script, whichever attempt reached it. */
    public String identityStep() {
        return rewindsUsed ? generations().identity(step.get()) : step.get();
    }

    /** The stable id of the step running on this thread (e.g. inside a tool or approval callback). */
    public String currentStep() {
        return step.get();
    }

    /**
     * For tools and approval callbacks that need a human mid-step: returns the recorded answer, or
     * suspends the run ({@link RunSuspended}) until one is recorded under the returned step id.
     * The step is re-run on resume and this call then returns the answer.
     */
    public String awaitHuman(String key, String message) {
        String id = step.get() + "#" + key;
        return journal.get(id).map(e -> String.valueOf(e.value())).orElseThrow(() -> new RunSuspended(id, message));
    }

    /**
     * {@code sleep 90s}. The wake time is journaled before anything waits, so a resumed run (or a second
     * executor on the same journal) keeps the original wake time and never lengthens the sleep. With a
     * durable journal a sleep longer than {@link #INLINE_SLEEP} pauses the run ({@link RunSuspended},
     * reason SLEEP) and holds no thread; otherwise the sleep is waited out in place.
     */
    private void executeSleep(io.github.llm4j.loom.ast.SleepStmt sleep) {
        String stepId = step.get();
        var recorded = journal.get(stepId).filter(e -> "sleep".equals(e.kind()));
        boolean resumed = recorded.isPresent();
        Instant wakeAt;
        if (resumed) {
            wakeAt = Instant.parse(String.valueOf(recorded.get().value()));
        } else {
            wakeAt = clock.instant().plus(sleep.getDuration());
            journal.put(stepId, new RunJournal.Entry("sleep", wakeAt.toString()));
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("wakeAt", wakeAt.toString());
        data.put("millis", String.valueOf(sleep.getDuration().toMillis()));
        data.put("step", stepId);
        if (!resumed) {
            data.put("state", "start");
            trace(TraceEvent.SLEEP, null, "sleeping " + sleep.getText() + ", until " + wakeAt, data);
            auditLogger.logConversationEvent(sessionId, null, "sleep_started", new java.util.LinkedHashMap<>(data));
        }
        Duration remaining = Duration.between(clock.instant(), wakeAt);
        if (!remaining.isNegative() && !remaining.isZero()) {
            if (journal.isDurable() && remaining.compareTo(INLINE_SLEEP) > 0) {
                throw new RunSuspended(stepId, wakeAt, "sleeping " + sleep.getText());
            }
            try {
                sleeper.sleep(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while sleeping at " + stepId, e);
            }
        }
        Map<String, Object> done = new java.util.LinkedHashMap<>(data);
        done.put("state", "end");
        trace(TraceEvent.SLEEP, null, "woke after " + sleep.getText(), done);
        auditLogger.logConversationEvent(sessionId, null, "sleep_ended", new java.util.LinkedHashMap<>(done));
    }

    /**
     * Called instead of running a delegate whose result was already recorded (a resumed run), so
     * embedders can restore their own bookkeeping. The variable has already been set.
     */
    protected void onDelegateReplayed(DelegateStmt stmt, AgentDef agentDef, Object value) {
        // No-op by default.
    }

    /** The clock for budget windows, rate-limit waits and suspension times (tests use a fixed one). */
    public void setClock(java.time.Clock clock) {
        this.clock = clock != null ? clock : java.time.Clock.systemUTC();
    }

    public java.time.Clock getClock() {
        return clock;
    }

    /** How inline waits for rate limits are done (tests record them instead of sleeping). */
    public void setSleeper(io.github.llm4j.ratelimit.Sleeper sleeper) {
        this.sleeper = sleeper != null ? sleeper : io.github.llm4j.ratelimit.Sleeper.SYSTEM;
    }

    /** Told whenever the run pauses for a limit, before {@link RunSuspended} leaves {@code executeWorkflow}. */
    /**
     * Receives the run's events live (thoughts, tool calls, spend, …). Add listeners before
     * {@link #initialize()}: agents are wired to report only when someone listens.
     */
    public void addTraceListener(TraceListener listener) {
        tracing.addListener(listener);
    }

    /** {@code researcher@v2} for the prompt the named agent runs, or empty when it runs an inline one or none resolves. */
    public java.util.Optional<String> promptLabel(String agentName) {
        return script.getAgents().stream().filter(a -> a.getName().equals(agentName)).findFirst()
                .flatMap(this::promptUsed).map(io.github.llm4j.loom.prompt.PromptUse::label);
    }

    /** The prompt file or registry prompt this agent runs, if it names one that resolves (the id, version and a hash of the text). */
    java.util.Optional<io.github.llm4j.loom.prompt.PromptUse> promptUsed(io.github.llm4j.loom.ast.AgentDef agent) {
        if (promptCatalog == null || agent.getPromptRef() == null) return java.util.Optional.empty();
        return promptCatalog.usesOf(script).stream().filter(u -> u.agent().equals(agent.getName()) && u.resolved()).findFirst();
    }

    /** The statistics of the model and tool calls made so far in this run (kept whether or not anyone traces). */
    public io.github.llm4j.telemetry.RunStats stats() {
        return tracing.stats();
    }

    boolean tracing() {
        return tracing.tracing();
    }

    /** Tells trace listeners; free when nobody listens. */
    void trace(String type, String agent, String text, Map<String, Object> data) {
        tracing.trace(type, agent, text, data);
    }

    /**
     * A read-only view of this run for an observer (see {@link io.github.llm4j.loom.observe.ObservableRun}). Take it and add
     * its listeners before {@link #initialize()}.
     */
    public io.github.llm4j.loom.observe.ObservableRun observable() {
        HarnessExecutor self = this;
        return new io.github.llm4j.loom.observe.ObservableRun() {
            @Override public String runId() { return self.runId; }
            @Override public RunJournal journal() { return self.journal; }
            @Override public LoomScript script() { return self.script; }
            @Override public io.github.llm4j.secret.SecretStore secretStore() { return self.secretStore; }
            @Override public java.util.Map<String, String> toolReach() { return self.toolReach; }
            @Override public java.util.Optional<io.github.llm4j.loom.autonomy.Ledger> ledger() { return java.util.Optional.ofNullable(self.ledger); }
            @Override public java.util.Optional<io.github.llm4j.loom.autonomy.LevelStore> levelStore() { return java.util.Optional.ofNullable(self.levelStore); }
            @Override public io.github.llm4j.budget.Budget runBudget() { return self.getRunBudget(); }
            @Override public SpendReport spend() { return self.spend(); }
            @Override public RunControl control() { return self.control; }
            @Override public Map<String, Object> variables() { return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(self.context.getAll())); }
            @Override public java.time.Clock clock() { return self.clock; }
            @Override public io.github.llm4j.telemetry.RunStats stats() { return self.stats(); }
            @Override public void addTraceListener(TraceListener listener) { self.addTraceListener(listener); }
            @Override public void addSuspensionListener(java.util.function.Consumer<RunSuspended> listener) { self.addSuspensionListener(listener); }
        };
    }

    public void addSuspensionListener(java.util.function.Consumer<RunSuspended> listener) {
        suspensionListeners.add(listener);
    }

    /**
     * Where resume triggers and the script's schedules are kept. With a store and a {@link #setRunId run id},
     * a run that pauses for a limit leaves a trigger to resume it; {@code schedule} blocks are written to
     * the store instead of running in memory.
     */
    public void setTriggerStore(io.github.llm4j.loom.trigger.TriggerStore store) {
        this.triggerStore = store;
    }

    public io.github.llm4j.loom.trigger.TriggerStore getTriggerStore() {
        return triggerStore;
    }

    /** The id a resume trigger names, so the host can rebuild this run (e.g. its run directory). */
    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getRunId() {
        return runId;
    }

    /** How schedule triggers refer to this script (e.g. its path); part of their ids. Default "script". */
    public void setScriptRef(String scriptRef) {
        this.scriptRef = scriptRef != null ? scriptRef : "script";
    }

    /** Gives one agent one task, waiting out or failing on limits (a scheduled agent task). */
    public io.github.llm4j.agent.AgentResult runAgentTask(String agentName, String task) {
        ReActAgent agent = activeAgents.get(agentName);
        if (agent == null) throw new IllegalArgumentException("Agent not found: " + agentName);
        return limits.runUnderLimits(agent, task);
    }

    /**
     * Lenient mode: features the runtime doesn't support yet (agent memory, unknown guardrail types) are
     * warnings instead of load errors. Unknown names and missing secrets are always errors.
     */
    public void setLenient(boolean lenient) {
        this.lenient = lenient;
    }

    /** Where {@code env.NAME} values come from (default: the process environment). */
    /**
     * Where {@code secret.NAME} references in the script are resolved, and where a secret named like an environment variable ({@code GEMINI_API_KEY})
     * takes precedence over that variable. The consumer chooses the store and protects it; the executor only reads it. Set it before {@link #initialize()}.
     */
    public void setSecretStore(io.github.llm4j.secret.SecretStore store) {
        this.secretStore = store;
    }

    public io.github.llm4j.secret.SecretStore getSecretStore() {
        return secretStore;
    }

    /**
     * Resolves a credential reference: {@code secret:NAME} is read from the secret store only; any other name is the store's secret of that name
     * if there is one (a stored secret wins over the ambient environment), else the environment variable. Null when there is none.
     */
    String credential(String key) {
        if (key.startsWith("secret:")) {
            String name = key.substring("secret:".length());
            return secretStore != null && secretStore.contains(name) ? secretStore.resolve(name) : null;
        }
        if (secretStore != null && io.github.llm4j.secret.SecretNames.isValid(key) && secretStore.contains(key)) return secretStore.resolve(key);
        return envLookup.apply(key);
    }

    /** The value of an option that is a reference (null when unset), for scrubbing it from anything recorded. */
    String credentialValue(io.github.llm4j.loom.ast.ToolDef.OptionValue v) {
        if (v.fromInvoker()) return null; // known only to a run, and scrubbed there
        try {
            return v.isReference() ? credentials.apply(v.lookupKey()) : v.value();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void setEnvLookup(java.util.function.Function<String, String> envLookup) {
        this.envLookup = envLookup != null ? envLookup : System::getenv;
    }

    /** How knowledge bases embed text (default: gemini/…, and onnx/… or djl/… with the addons module). */
    public void setEmbeddingFactory(io.github.llm4j.loom.knowledge.EmbeddingFactory factory) {
        this.embeddingFactory = factory;
    }

    private io.github.llm4j.loom.knowledge.EmbeddingFactory embeddings() {
        if (embeddingFactory == null) embeddingFactory = new io.github.llm4j.loom.knowledge.DefaultEmbeddingFactory(envLookup, secretStore);
        return embeddingFactory;
    }

    /** A knowledge base's index, after {@code initialize()}. */
    public io.github.llm4j.loom.knowledge.KnowledgeIndex getKnowledge(String name) {
        return knowledge.get(name);
    }

    /** Adds a kind of tool scripts can declare with {@code use: <name>}. */
    public void addToolKind(io.github.llm4j.loom.tools.ToolKind kind) {
        toolFactory.register(kind);
    }

    /** Where relative paths in the script (OpenAPI specs, knowledge sources) are resolved. Default: working directory. */
    public void setBaseDir(Path dir) {
        this.baseDir = dir != null ? dir.toAbsolutePath() : Path.of("").toAbsolutePath();
    }

    /** The load-time checks for this script, as {@code initialize()} runs them (see {@link ScriptValidator}). */
    public ScriptValidator.Context validationContext() {
        java.util.Set<String> tools = new java.util.HashSet<>(toolRegistry.names());
        script.getTools().forEach(t -> tools.add(t.getName()));
        tools.addAll(io.github.llm4j.loom.tools.ToolFactory.BUILT_INS.keySet());
        return new ScriptValidator.Context()
                .registeredTools(tools)
                .tasks(getTaskRegistry())
                .env(envLookup)
                .lenient(lenient)
                .humanInterface(humanInterface != null)
                .baseDir(baseDir)
                .prompts(promptCatalog)
                .check(validation::checkAgentSettings)
                .check(validation::checkCompactors)
                .check(validation::checkToolsAndApprovals)
                .check(validation::checkKnowledge)
                .check(validation::checkProviders);
    }

    private final ExecutorValidation validation = new ExecutorValidation(new ExecutorValidation.Host() {
        @Override public LoomScript script() { return script; }
        @Override public Path baseDir() { return baseDir; }
        @Override public String credential(String key) { return credentials.apply(key); }
        @Override public String credentialValue(io.github.llm4j.loom.ast.ToolDef.OptionValue v) { return HarnessExecutor.this.credentialValue(v); }
        @Override public io.github.llm4j.secret.SecretStore secretStore() { return secretStore; }
        @Override public String modelProblem(String model) { return llmClientFactory.problem(model); }
        @Override public String embeddingProblem(String model) { return embeddings().problem(model); }
        @Override public io.github.llm4j.loom.ast.ProviderDef declaredProvider(String model) { return HarnessExecutor.this.declaredProvider(model); }
        @Override public io.github.llm4j.secret.SecretRef storedKey(io.github.llm4j.loom.ast.ToolDef.OptionValue key) { return HarnessExecutor.this.storedKey(key); }
        @Override public List<String> toolProblems(io.github.llm4j.loom.ast.ToolDef def) { return toolFactory.problems(def, credentials, baseDir); }
        @Override public String agentToolProblem(io.github.llm4j.loom.ast.ToolDef def, String agent, boolean approved) { return toolFactory.agentProblem(def, credentials, agent, approved); }
        @Override public boolean hostRegistersTool(String name) { return toolRegistry.getTool(name) != null; }
        @Override public java.util.Set<String> customCompactorNames() { return HarnessExecutor.this.customCompactorNames(); }
        @Override public io.github.llm4j.loom.context.CompactorRegistry compactors() { return HarnessExecutor.this.compactors(); }
        @Override public io.github.llm4j.loom.context.WindowLayers contextWindows() { return HarnessExecutor.this.contextWindows(); }
        @Override public List<String> modelsOf(AgentDef agent) { return HarnessExecutor.this.modelsOf(agent); }
    });

    // ── Model providers ─────────────────────────────────────────────────────────────────────

    /** The declared provider a model name refers to ({@code Box/llama3} → Box), or null. */
    private io.github.llm4j.loom.ast.ProviderDef declaredProvider(String model) {
        int slash = model == null ? -1 : model.indexOf('/');
        if (slash <= 0) return null;
        String name = model.substring(0, slash);
        return script.getProviders().stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
    }

    /** A client for a model: at a provider the script declared, else from the client factory. */
    io.github.llm4j.LLMClient clientFor(String model) {
        io.github.llm4j.loom.ast.ProviderDef declared = declaredProvider(model);
        if (declared == null) return llmClientFactory.createClient(model);
        String baseUrl = option(declared, "base_url");
        io.github.llm4j.loom.ast.ToolDef.OptionValue keyOption = declared.getOptions().get("api_key");
        io.github.llm4j.secret.SecretRef ref = storedKey(keyOption);
        // a key in the store is handed over by reference: the provider fetches it for each request, and the secret's host binding applies
        String key = ref != null || keyOption == null ? null : option(declared, "api_key");
        return llmClientFactory.createClient(new ProviderSpec(declared.getName(), declared.getKind(), baseUrl, key, ref),
                model.substring(declared.getName().length() + 1));
    }

    /** The key as a reference into the secret store when that is where it lives, else null. */
    private io.github.llm4j.secret.SecretRef storedKey(io.github.llm4j.loom.ast.ToolDef.OptionValue key) {
        if (key == null || !key.isReference() || secretStore == null) return null;
        return secretStore.contains(key.value()) ? io.github.llm4j.secret.SecretRef.of(secretStore, key.value()) : null;
    }

    private String option(io.github.llm4j.loom.ast.ToolDef def, String name) {
        io.github.llm4j.loom.ast.ToolDef.OptionValue v = def.getOptions().get(name);
        if (v == null) return null;
        return v.isReference() ? credentials.apply(v.lookupKey()) : v.value();
    }

    // ── Agent memory, voice and guard settings ──────────────────────────────────────────────

    /**
     * Where each model's context window comes from. The default is what the command line named (see {@link io.github.llm4j.loom.context.ContextFiles}),
     * else the catalogue shipped with ai-agent4j.
     */
    public void setContextWindows(io.github.llm4j.loom.context.WindowLayers layers) {
        this.contextWindows = layers;
    }

    io.github.llm4j.loom.context.WindowLayers contextWindows() {
        if (contextWindows == null) contextWindows = io.github.llm4j.loom.context.ContextFiles.current().windowLayers();
        return contextWindows;
    }

    /** Your own compaction strategies by name (a {@code .loct} file, or code). Starts with the files the command line named. */
    public io.github.llm4j.loom.context.CompactorRegistry compactors() {
        if (compactors == null) {
            compactors = new io.github.llm4j.loom.context.CompactorRegistry();
            for (Path f : io.github.llm4j.loom.context.ContextFiles.current().loctFiles()) compactors.load(f);
        }
        return compactors;
    }

    /** Reads a {@code .loct} file into {@link #compactors()}; returns what is wrong with it. */
    public java.util.List<io.github.llm4j.loom.context.CompactorRegistry.Problem> loadCompactors(Path file) {
        return compactors().load(file);
    }

    /** Makes a strategy of yours available to scripts by this name. */
    public void registerCompactor(String name, io.github.llm4j.context.ContextCompactor compactor) {
        compactors().register(name, compactor);
    }

    /** The names a {@code .loct} file (or code) maps to strategies. */
    java.util.Set<String> customCompactorNames() {
        return compactors().names();
    }

    private final Map<String, io.github.llm4j.context.ContextCompactor> contextCompactors = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Bounds Loom's shared transcript when an agent asks for it: {@code memory { max_tokens, compact }}, or else a quarter of the usable window of an
     * agent that has a {@code context} block, with that agent's strategy. Other memory engines are left as the host made them.
     */
    private void boundTranscript() {
        if (!(memoryEngine instanceof TranscriptAccumulationEngine transcript) || transcript.bounded()) return;
        boolean asked = script.getAgents().stream().anyMatch(a -> a.getMemory() != null && a.getMemory().has("max_tokens") || a.getContext() != null);
        if (asked) transcript.limits(this::transcriptLimit);
    }

    private TranscriptAccumulationEngine.Limit transcriptLimit(AgentDef a) {
        AgentDef.MemoryConfig mem = a.getMemory();
        if (mem != null && mem.has("max_tokens")) {
            io.github.llm4j.context.ContextCompactor compactor;
            String name = mem.getCompact();
            try {
                compactor = compactors().has(name) ? compactors().create(name, Map.of(), null)
                        : io.github.llm4j.context.ContextCompactors.byName("summarize".equals(name) ? "drop_oldest" : name, Map.of());
            } catch (RuntimeException e) {
                compactor = io.github.llm4j.context.ContextCompactors.dropOldest();
            }
            return new TranscriptAccumulationEngine.Limit(mem.getMaxTokens(), compactor, 0.5, 2);
        }
        AgentDef.ContextConfig cfg = a.getContext();
        io.github.llm4j.context.ContextCompactor compactor = cfg == null ? null : contextCompactors.get(a.getName());
        if (compactor == null) return null;
        return ContextWiring.governing(contextWindows(), modelsOf(a))
                .map(f -> new TranscriptAccumulationEngine.Limit(Math.max(1, f.window().usableInput() / 4), compactor, 0.5, 2))
                .orElse(null);
    }

    /** Gives an agent with a {@code context} block its policy, its model's window and its compaction strategy. Agents without one are left as they were. */
    private void applyContext(AgentDef agentDef, ReActAgent.Builder builder) {
        AgentDef.ContextConfig cfg = agentDef.getContext();
        if (cfg == null) return;
        java.util.Optional<io.github.llm4j.loom.context.WindowLayers.Found> found = ContextWiring.governing(contextWindows(), modelsOf(agentDef));
        builder.contextPolicy(ContextWiring.policy(cfg, found.map(io.github.llm4j.loom.context.WindowLayers.Found::window).orElse(null)));
        io.github.llm4j.LLMClient summarizer = cfg.getSummarizer() == null ? null
                : budgets.metered(clientFor(cfg.getSummarizer()), agentDef, cfg.getSummarizer());
        try {
            io.github.llm4j.context.ContextCompactor compactor = ContextWiring.compactor(cfg, compactors(), summarizer);
            if (compactor != null) {
                builder.compaction(compactor);
                contextCompactors.put(agentDef.getName(), compactor);
            }
        } catch (IllegalArgumentException e) {
            throw new LoomLoadException(List.of(new ScriptValidator.Problem(cfg.getLine(), "agent " + agentDef.getName(), "context: " + e.getMessage(), ScriptValidator.Severity.ERROR)));
        }
    }


    /** Builds (or brings up to date) every knowledge base's index. */
    private void indexKnowledge() {
        for (KnowledgeDef kb : script.getKnowledgeBases()) {
            try {
                io.github.llm4j.loom.knowledge.KnowledgeIndex index = io.github.llm4j.loom.knowledge.KnowledgeIndexer.index(
                        kb, embeddings().create(kb.getEmbeddingProvider()), baseDir);
                knowledge.put(kb.getName(), index);
                log.info("📚 " + kb.getName() + ": " + index.stats());
                Map<String, Object> data = new java.util.LinkedHashMap<>();
                data.put("kb", kb.getName());
                data.put("files", String.valueOf(index.stats().files()));
                data.put("skipped", String.valueOf(index.stats().skipped()));
                data.put("chunks", String.valueOf(index.stats().chunks()));
                data.put("embedded", String.valueOf(index.stats().embedded()));
                auditLogger.logConversationEvent(sessionId, null, "knowledge_indexed", data);
            } catch (Exception e) {
                throw new LoomLoadException(List.of(new ScriptValidator.Problem(kb.getLine(), "knowledge " + kb.getName(),
                        "indexing failed: " + e.getMessage(), ScriptValidator.Severity.ERROR)));
            }
        }
    }

    /** How many times this run has been resumed after pausing for a limit. */
    public int getResumes() {
        return resumes;
    }

    /** Prices for cost budgets and cost reporting (see {@link io.github.llm4j.budget.PriceTable}). */
    public void setPriceTable(io.github.llm4j.budget.PriceTable prices) {
        budgets.priceTable(prices);
    }

    /** How tokens are estimated before a call and when a provider reports none. */
    public void setTokenEstimator(io.github.llm4j.budget.TokenEstimator estimator) {
        budgets.tokenEstimator(estimator);
    }

    /**
     * Replaces the script's run limits (null keeps the script's value), e.g. from {@code weave --max-tokens}.
     * Call before {@link #initialize()}.
     */
    public void setBudgetOverrides(Long tokens, Long calls, java.math.BigDecimal cost) {
        budgets.overrides(tokens, calls, cost);
    }

    /** The run budget, or null when the script declares no budgets. */
    public io.github.llm4j.budget.Budget getRunBudget() {
        return budgets.runBudget();
    }

    /** An agent's own budget (its {@code budget { }} block), or null. */
    public io.github.llm4j.budget.Budget getAgentBudget(String agentName) {
        return budgets.agentBudget(agentName);
    }

    /** What the run has spent so far: totals, per agent and per step. */
    public SpendReport spend() {
        return budgets.spend();
    }

    /** Variables as the current block sees them: workflow variables plus block-local names. */
    VariableContext view() {
        Map<String, Object> l = locals.get();
        return l.isEmpty() ? context : new ScopedContext(l, context);
    }

    private Tool resolveTool(String name) {
        Tool tool = resolveToolAsDeclared(name);
        if (simulate) return SimulatingTool.of(tool);
        return rewindsUsed && !SimulatingTool.runsAsItIs(tool) ? new RecordingTool(tool, this) : tool;
    }

    private Tool resolveToolAsDeclared(String name) {
        for (io.github.llm4j.loom.ast.ToolDef def : script.getTools()) {
            if (def.getName().equals(name)) return createTool(def);
        }
        Tool registered = toolRegistry.getTool(name);
        if (registered != null) return registered;
        io.github.llm4j.loom.ast.ToolDef builtIn = io.github.llm4j.loom.tools.ToolFactory.builtIn(name);
        if (builtIn != null) return createTool(builtIn);
        throw new IllegalStateException("tool " + name + " is not defined"); // validated earlier
    }

    private final Map<String, Tool> createdTools = new java.util.concurrent.ConcurrentHashMap<>();

    private Tool createTool(io.github.llm4j.loom.ast.ToolDef def) {
        return createdTools.computeIfAbsent(def.getName(), n -> {
            try {
                return toolFactory.create(def, credentials, baseDir, effectContext(), name -> new InvokerSource(name, this::invoker));
            } catch (Exception e) {
                throw new LoomLoadException(List.of(new ScriptValidator.Problem(def.getLine(), "tool " + def.getName(),
                        "can't be created: " + e.getMessage(), ScriptValidator.Severity.ERROR)));
            }
        });
    }

    private io.github.llm4j.agent.tool.EffectContext effectContext;

    /** What generic tools need from this run: audit, trace, the journal, the current step, and files to keep out of reach. */
    private synchronized io.github.llm4j.agent.tool.EffectContext effectContext() {
        if (effectContext == null) effectContext = new RunEffectContext(this);
        return effectContext;
    }

    /** Whoever the run on this thread is for, or null when this thread is not running one. */
    InvokerCredentials invoker() {
        return threadScope.invoker.get();
    }

    long currentAttempt() {
        return attempt.get();
    }

    java.time.Clock clock() {
        return clock;
    }

    io.github.llm4j.ratelimit.Sleeper sleeper() {
        return sleeper;
    }

    /** The run journal's file and the trigger store's directory: tools that touch files must stay out of them. */
    java.util.Set<Path> reservedPaths() {
        java.util.Set<Path> out = new java.util.HashSet<>();
        if (journal instanceof io.github.llm4j.loom.runtime.FileRunJournal f) {
            out.add(f.path().toAbsolutePath().normalize());
            if (f.path().toAbsolutePath().getParent() != null) out.add(f.path().toAbsolutePath().getParent().normalize());
        }
        if (triggerStore instanceof io.github.llm4j.loom.trigger.FileTriggerStore t) out.add(t.dir().toAbsolutePath().normalize());
        out.addAll(autonomyPaths());
        return out;
    }

    private ApprovalGate approvalGate;

    synchronized ApprovalGate approvals() {
        if (approvalGate == null) approvalGate = new ApprovalGate(this);
        return approvalGate;
    }

    /** Runs a block, giving each statement a stable step id under the current one. */
    private void runBlock(List<Statement> statements, String key) {
        String parent = step.get();
        String block = parent + "/" + key;
        boolean root = rootNext.get();
        rootNext.set(false);
        Rewinder.Frame frame = new Rewinder.Frame(block, root, root ? new java.util.HashMap<>(context.getAll()) : null, root ? memoryEngine.mark() : null);
        java.util.ArrayDeque<Rewinder.Frame> stack = frames.get();
        stack.push(frame);
        try {
            for (int i = 0; i < statements.size(); i++) {
                Generations generations = generations();
                int generation = generations.current(block, i);
                step.set(block + i + (generation > 1 ? "~" + generation : ""));
                frame.index = i;
                if (rewindsUsed) {
                    rewinder.begin(block, i);
                    if (generation > 1) context.setVariable("_generation", String.valueOf(generation));
                }
                try {
                    control.checkpoint(step.get()); // an observer may have paused, suspended or stopped the run
                    executeStatement(statements.get(i));
                    if (stopAt != null) stopPoint(step.get());
                } catch (Rewinder.RewindSignal signal) {
                    if (!signal.block.equals(block)) throw signal;
                    rewinder.restore(frame, signal.checkpoint);
                    i = signal.from - 1; // the loop's increment lands on the first statement of the new generation
                }
            }
        } finally {
            stack.pop();
            step.set(parent);
        }
    }

    /** Runs {@code work} on another thread with this thread's run state, at the given step and with the given block-local names. */
    private CompletableFuture<Void> forkWith(String stepId, Map<String, Object> names, Runnable work) {
        ThreadScope.Snapshot scope = threadScope.capture().at(stepId, names);
        return CompletableFuture.runAsync(() -> {
            try (ThreadScope.Installed ignored = scope.install()) {
                work.run();
            }
        }, BRANCHES);
    }

    /**
     * Waits for every forked branch to finish — branches that can still work are never cut short, so
     * their results are journaled. Then a failure in any branch surfaces as itself; if branches only
     * paused, the run pauses once: for a human if any branch waits for one, else until the latest reset.
     */
    private static void joinAll(List<? extends CompletableFuture<?>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            return;
        } catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException ignored) {
            // look at each branch below
        }
        RuntimeException failure = null;
        List<RunSuspended> paused = new java.util.ArrayList<>();
        for (CompletableFuture<?> f : futures) {
            if (!f.isCompletedExceptionally()) continue;
            Throwable cause;
            try {
                f.join();
                continue;
            } catch (java.util.concurrent.CompletionException e) {
                cause = e.getCause() != null ? e.getCause() : e;
            } catch (java.util.concurrent.CancellationException e) {
                cause = e;
            }
            if (cause instanceof RunSuspended rs) {
                paused.add(rs);
            } else if (failure == null) {
                failure = cause instanceof RuntimeException re ? re : new java.util.concurrent.CompletionException(cause);
            }
        }
        if (failure != null) throw failure;
        if (paused.isEmpty()) return;
        RunSuspended latest = null;
        for (RunSuspended rs : paused) {
            if (rs.resumeAt() == null) throw rs; // a person must answer first; limits are re-checked on resume
            if (latest == null || rs.resumeAt().isAfter(latest.resumeAt())) latest = rs;
        }
        throw latest;
    }

    @Override
    public void initialize() {
        // ── 0. Configure audit logger from script-level audit block ──────────
        if (script.getAuditConfig() != null) {
            AuditConfig cfg = script.getAuditConfig();
            if ("file".equalsIgnoreCase(cfg.getLogger())) {
                this.auditLogger = new FileAuditLogger(Path.of(cfg.getPath()));
                log.info("Audit logging enabled → " + cfg.getPath());
            }
        }

        // ── 0b. Check the script: fail now, with every problem, rather than mid-run ──
        for (ScriptValidator.Problem warning : new ScriptValidator().validateOrThrow(script, validationContext())) {
            log.warning(warning.toString());
        }

        // ── 1. Boot MCP servers ───────────────────────────────────────────────
        for (McpServerDef mcpDef : script.getMcpServers()) {
            if (mcpDef.getCmd() == null || mcpDef.getCmd().isBlank()) {
                log.warning("MCP server '" + mcpDef.getName() + "' has no cmd — skipping.");
                continue;
            }
            try {
                List<String> cmdParts = Arrays.asList(mcpDef.getCmd().split("\\s+"));
                StdioMcpTransport transport = new StdioMcpTransport(cmdParts, mcpDef.getEnv().isEmpty() ? null : mcpDef.getEnv());
                McpClient client = new McpClient(transport);
                client.initialize();
                mcpClients.put(mcpDef.getName(), client);
                log.info("MCP server initialised: " + mcpDef.getName());
            } catch (Exception e) {
                throw new LoomLoadException(List.of(new ScriptValidator.Problem(mcpDef.getLine(), "mcp " + mcpDef.getName(),
                        "failed to start `" + mcpDef.getCmd() + "`: " + e.getMessage(), ScriptValidator.Severity.ERROR)));
            }
        }

        // ── 1b. Budgets ───────────────────────────────────────────────────────
        budgets.setUp();

        // ── 1c. Knowledge bases ───────────────────────────────────────────────
        indexKnowledge();

        // ── 2. Build agents ───────────────────────────────────────────────────
        for (AgentDef agentDef : script.getAgents()) {
            String systemPrompt = prompts.resolveSystemPrompt(agentDef);

            // Determine LLM Client (Routing vs Regular)
            io.github.llm4j.LLMClient llmClient = null;
            if (agentDef.getRoutingPolicy() != null) {
                RoutingPolicyDef policy = script.getRoutingPolicies().stream()
                        .filter(p -> p.getName().equals(agentDef.getRoutingPolicy()))
                        .findFirst()
                        .orElse(null);
                
                if (policy != null) {
                    boolean fallback = policy.getStrategy() != null
                            && "fallback".equals(ScriptValidator.routingStrategy(policy.getStrategy()));
                    RoutingLLMClient.Builder routingBuilder = RoutingLLMClient.builder()
                            .strategy(fallback ? new io.github.llm4j.routing.FallbackRoutingStrategy() : new CostAwareRoutingStrategy());
                    // fallback: one tier, tried in the order written (primary first); cost-aware: primary is the strong tier
                    routingBuilder.addClient(fallback ? ProviderTier.FAST_CHEAP : ProviderTier.REASONING,
                            budgets.metered(clientFor(policy.getPrimaryModel()), agentDef, policy.getPrimaryModel()));
                    for (String fb : policy.getFallbackModels()) {
                        routingBuilder.addClient(fallback ? ProviderTier.FAST_CHEAP : ProviderTier.BALANCED,
                                budgets.metered(clientFor(fb), agentDef, fb));
                    }
                    llmClient = routingBuilder.build();
                }
            }
            
            if (llmClient == null) {
                llmClient = budgets.metered(clientFor(agentDef.getModel()), agentDef, agentDef.getModel());
            }
            if (script.getDecisions().stream().anyMatch(d -> d.getLadder() != null && agentDef.getName().equals(d.getAgent()))) {
                // a ladder compares the model the provider says answered with the one that answered before: a silent change behind an alias starts a new epoch
                String agentName = agentDef.getName();
                llmClient = new ServedModelRecorder(llmClient, served -> servedModels.put(agentName, served));
            }
            if (agentDef.getGuard() != null) {
                String judge = agentDef.getGuard().getBiasModel();
                io.github.llm4j.fairness.BiasMonitor monitor = judge == null
                        ? new io.github.llm4j.fairness.RuleBasedBiasMonitor()
                        : new io.github.llm4j.fairness.LLMBiasMonitor(budgets.metered(clientFor(judge), agentDef, judge));
                AgentGuard guard = new AgentGuard(agentDef.getName(), agentDef.getGuard(), monitor, this);
                guards.put(agentDef.getName(), guard);
                llmClient = guard.wrap(llmClient);
            }
            AgentMemory agentMemory = null;
            if (agentDef.getMemory() != null) {
                try {
                    AgentDef.MemoryConfig mc = agentDef.getMemory();
                    agentMemory = new AgentMemory(agentDef.getName(), mc, baseDir,
                            mc.getFacts() == null ? null : embeddings().create(mc.getEmbedding()));
                } catch (Exception e) {
                    throw new LoomLoadException(List.of(new ScriptValidator.Problem(agentDef.getMemory().getLine(),
                            "agent " + agentDef.getName(), "memory can't be opened: " + e.getMessage(), ScriptValidator.Severity.ERROR)));
                }
                memories.put(agentDef.getName(), agentMemory);
            }
            if (agentDef.getVoice() != null) voices.put(agentDef.getName(), new AgentVoice(agentDef.getVoice(), credentials, baseDir));

            ReActAgent.Builder agentBuilder = ReActAgent.builder().llmClient(llmClient);
            if (agentDef.getTemperature() != null) {
                agentBuilder.temperature(agentDef.getTemperature());
            }
            boolean searchesKnowledge = agentDef.getKnowledgeBases().stream()
                    .anyMatch(kb -> knowledge.get(kb).def().getMode() == KnowledgeDef.Mode.TOOL);
            boolean savesFacts = agentMemory != null && agentMemory.keepsFacts();
            if (agentDef.getTools().isEmpty() && agentDef.getMcpServers().isEmpty() && !searchesKnowledge && !savesFacts) {
                agentBuilder.systemPrompt(systemPrompt);
            } else {
                // Tool-using agents keep the ReAct protocol (tool descriptions + JSON format);
                // a verbatim system prompt would hide their tools from the model.
                agentBuilder.instructions(systemPrompt);
            }

            // Tools: script declarations, then host-registered (.loot or Java), then built-ins
            for (String toolName : agentDef.getTools()) {
                Tool tool = resolveTool(toolName);
                if (script.getDecisions().stream().anyMatch(d -> agentDef.getName().equals(d.getAgent()))) tool = new EvidenceTool(tool, this);
                if (agentDef.isApproveAll() || agentDef.getApprove().contains(toolName)) {
                    tool = new io.github.llm4j.loom.tools.ApprovalTool(tool);
                }
                agentBuilder.addTool(tool);
            }
            if (agentDef.isApproveAll() || !agentDef.getApprove().isEmpty()) {
                String agentName = agentDef.getName();
                agentBuilder.approvalCallback((tool, args, thought) -> approvals().approve(agentName, tool, args, thought));
            }
            if (agentDef.getMaxIterations() != null) agentBuilder.maxIterations(agentDef.getMaxIterations());
            if (agentDef.isStreaming()) agentBuilder.streaming(true);
            if (agentDef.isReasoning()) agentBuilder.reasoning(true);
            List<io.github.llm4j.loom.knowledge.KnowledgeIndex> inContext = new java.util.ArrayList<>();
            for (String kbName : agentDef.getKnowledgeBases()) {
                io.github.llm4j.loom.knowledge.KnowledgeIndex index = knowledge.get(kbName);
                if (index.def().getMode() == KnowledgeDef.Mode.TOOL) agentBuilder.addTool(io.github.llm4j.loom.knowledge.Retriever.searchTool(index));
                else inContext.add(index);
            }
            if (!inContext.isEmpty()) retrievers.put(agentDef.getName(), new io.github.llm4j.loom.knowledge.Retriever(inContext));
            if (savesFacts) {
                String agentName = agentDef.getName();
                agentBuilder.addTool(agentMemory.factTool(() -> sessions.getOrDefault(step.get(), agentName)));
            }
            agentBuilder.auditLogger(auditLogger).sessionId(sessionId);
            // what counts as a read is Loom's call (it sees through the wrappers it puts around tools), so a stopped agent can tell whether it may have changed anything
            agentBuilder.effectClassifier((tool, args) -> !ReadClass.isRead(tool, args));
            applyContext(agentDef, agentBuilder);
            agentBuilder.addListener(tracing.statsAdapter());
            if (tracing()) agentBuilder.addListener(tracing.traceAdapter(agentDef.getName()));

            // MCP-sourced tools
            for (String serverName : agentDef.getMcpServers()) {
                McpClient mcpClient = mcpClients.get(serverName);
                if (mcpClient == null) {
                    log.warning("MCP server '" + serverName + "' not found for agent '" + agentDef.getName() + "'");
                    continue;
                }
                try {
                    for (Map<String, Object> toolMeta : mcpClient.listTools()) {
                        Tool adapter = new McpToolAdapter(mcpClient, toolMeta);
                        agentBuilder.addTool(adapter);
                        log.info("Bound MCP tool '" + toolMeta.get("name") + "' to agent '" + agentDef.getName() + "'");
                    }
                } catch (Exception e) {
                    log.severe("Failed to list tools from MCP server '" + serverName + "': " + e.getMessage());
                }
            }

            customizeAgent(agentDef, agentBuilder);
            ReActAgent agent = agentBuilder.build();
            activeAgents.put(agentDef.getName(), agent);
            boundTranscript();
            
            log.info("Initialized Agent: " + agentDef.getName());
        }

        // ── 3. Schedules ──────────────────────────────────────────────────────
        for (ScheduleDef sd : script.getSchedules()) {
            if (sd.getRunWorkflow() != null && script.getWorkflows().stream().noneMatch(w -> w.getName().equals(sd.getRunWorkflow()))) {
                throw new IllegalStateException("schedule " + sd.getName() + " (line " + sd.getLine() + "): run: "
                        + sd.getRunWorkflow() + " is not a workflow in this script");
            }
            if (sd.getAgentName() != null && script.getAgents().stream().noneMatch(a -> a.getName().equals(sd.getAgentName()))) {
                throw new IllegalStateException("schedule " + sd.getName() + " (line " + sd.getLine() + "): agent "
                        + sd.getAgentName() + " is not defined");
            }
        }
        if (triggerStore != null) {
            if (!script.getSchedules().isEmpty()) {
                List<String> written = io.github.llm4j.loom.trigger.Schedules.reconcile(script, scriptRef, triggerStore, clock.instant());
                log.info("Schedules stored as triggers: " + written);
            }
            return;
        }
        for (ScheduleDef sd : script.getSchedules()) {
            if (sd.getRunWorkflow() != null) {
                log.warning("schedule " + sd.getName() + " runs a workflow: give the executor a trigger store "
                        + "(setTriggerStore) to keep it — it is not run in memory.");
                continue;
            }
            log.warning("schedule " + sd.getName() + " runs in memory only: it is lost when this process stops. "
                    + "Give the executor a trigger store to keep it.");
            ReActAgent agent = activeAgents.get(sd.getAgentName());
            AgentScheduler scheduler = new AgentScheduler(agent);
            schedulers.put(sd.getName(), scheduler);

            Duration delay = parseDuration(sd.getInitialDelay());
            Duration period = sd.getEvery() != null ? sd.getEvery()
                    : sd.getPattern() != null && !sd.getPattern().isEmpty() ? parseDuration(sd.getPattern()) : null;
            if (period != null) {
                scheduler.scheduleRecurringTask(sd.getTask(), delay, period);
                log.info("Scheduled recurring task '" + sd.getName() + "' every " + period);
            } else {
                scheduler.scheduleTask(sd.getTask(), delay);
                log.info("Scheduled one-off task '" + sd.getName() + "' with delay " + sd.getInitialDelay());
            }
        }
    }

    @Override
    public void shutdown() {
        log.info("Shutting down HarnessExecutor...");
        schedulers.values().forEach(AgentScheduler::shutdown);
    }

    @Override
    public void executeWorkflow(String workflowName, Map<String, String> initialContext) {
        executeWorkflow(workflowName, initialContext, null);
    }

    /**
     * Runs a workflow for whoever {@code invoker} stands for. Only the outermost call uses it; a workflow {@code call}ed from another keeps the one
     * its run started with. An executor runs one outermost workflow at a time: its variables, journal and budgets belong to that run.
     *
     * @throws io.github.llm4j.secret.MissingInvokerCredentialException before anything runs, if the script needs credentials {@code invoker} cannot supply
     * @throws IllegalStateException if another outermost workflow is running on this executor
     */
    @Override
    public void executeWorkflow(String workflowName, Map<String, String> initialContext, InvokerCredentials invoker) {
        if (!step.get().isEmpty()) {
            runWorkflow(workflowName, initialContext);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("this executor is already running a workflow; an executor runs one at a time, "
                    + "so create one HarnessExecutor for each run");
        }
        try {
            InvokerCredentials who = invoker != null ? invoker : InvokerCredentials.none();
            InvokerRefs.require(script, who);
            threadScope.invoker.set(who);
            runWorkflow(workflowName, initialContext);
        } finally {
            threadScope.invoker.remove();
            running.set(false);
        }
    }

    private void runWorkflow(String workflowName, Map<String, String> initialContext) {
        // Hydrate initial variables
        if (initialContext != null) {
            initialContext.forEach(context::setVariable);
        }

        WorkflowDef targetWorkflow = script.getWorkflows().stream()
            .filter(w -> w.getName().equals(workflowName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowName));

        log.info("Starting workflow: " + workflowName);
        if (budgets.budgeting()) {
            budgets.restoreSpend();
            context.setVariable("_budget", budgets.view());
        }

        String parent = step.get();
        boolean topLevel = parent.isEmpty();
        if (topLevel) control.reset(); // a stop asked for an earlier run is not meant for this one
        if (topLevel) {
            beginRun();
            control.reset(); // a stop asked for an earlier run is not meant for this one
        }
        step.set(topLevel ? workflowName : parent + ">" + workflowName);
        String ended = "completed";
        Map<String, Object> endData = new java.util.LinkedHashMap<>();
        if (topLevel) {
            Map<String, Object> startData = new java.util.LinkedHashMap<>();
            startData.put("workflow", workflowName);
            if (runId != null) startData.put("run", runId);
            startData.put("resumed", String.valueOf(resumedFromPause));
            trace(TraceEvent.RUN_START, null, "workflow " + workflowName, startData);
        }
        try {
            rootNext.set(true);
            runBlock(targetWorkflow.getStatements(), "s");
            log.info("Workflow completed: " + workflowName);
        } catch (HandoffSignal hs) {
            log.info("Workflow terminated via handoff: " + hs.getMessage());
        } catch (RunSuspended paused) {
            ended = "suspended";
            endData.put("reason", paused.reason().name());
            if (paused.resumeAt() != null) endData.put("resumeAt", paused.resumeAt().toString());
            if (topLevel && paused.resumeAt() != null) recordSuspension(paused);
            throw paused;
        } catch (io.github.llm4j.loom.runtime.RunStopped stopped) {
            ended = "stopped";
            throw stopped;
        } catch (RuntimeException | Error failure) {
            ended = failure instanceof io.github.llm4j.budget.BudgetExceeded ? "budget" : "failed";
            endData.put("error", String.valueOf(failure.getMessage()));
            Map<String, Object> culprit = unhandledFailure.get();
            if (culprit != null) {
                endData.put("failed_kind", culprit.get("kind"));
                endData.put("failed_name", culprit.get("name"));
                endData.put("failed_step", culprit.get("step"));
                endData.put("failed_category", culprit.get("category"));
            }
            throw failure;
        } finally {
            step.set(parent);
            if (topLevel) {
                endData.put("workflow", workflowName);
                if (runId != null) endData.put("run", runId);
                endData.put("status", ended);
                io.github.llm4j.telemetry.RunStats totals = tracing.stats();
                endData.put("model_calls", totals.totalModelCalls());
                endData.put("tool_calls", totals.totalToolCalls());
                endData.put("tool_failures", totals.totalToolFailures());
                endData.put("retries", totals.totalRetries());
                endData.put("failovers", totals.totalFailovers());
                endData.put("compactions", tracing.compactions());
                trace(TraceEvent.RUN_END, null, "workflow " + workflowName + " " + ended, endData);
            }
        }
    }

    @Override
    public VariableContext getContext() {
        return context;
    }

    /**
     * Internal signal thrown when a {@code handoff} statement is executed.
     * Propagates up through the workflow loop to terminate execution cleanly.
     */
    static final class HandoffSignal extends RuntimeException {
        HandoffSignal(String message) { super(message, null, true, false); }
    }

    private void executeStatement(Statement stmt) {
        if (stmt instanceof CheckpointStmt checkpoint) {
            java.util.ArrayDeque<Rewinder.Frame> stack = frames.get();
            rewinder.checkpoint(checkpoint, stack.peek(), stack.peek().index);
            if (stopAt != null) stopPoint(checkpoint.getName());
        } else if (stmt instanceof RewindStmt rewind) {
            rewinder.rewind(rewind, frames.get());
        } else if (stmt instanceof io.github.llm4j.loom.ast.DecideStmt decide) {
            decider.decide(decide);
        } else if (stmt instanceof NoteStmt note) {
            String noteText = resolvePayload(note.getMessage());
            log.info("NOTE: " + noteText);
            trace(TraceEvent.NOTE, null, noteText, null);
        } else if (stmt instanceof io.github.llm4j.loom.ast.SleepStmt sleep) {
            executeSleep(sleep);
        } else if (stmt instanceof io.github.llm4j.loom.ast.RunStmt run) {
            taskRunner.run(run);
        } else if (stmt instanceof DelegateStmt del) {
            delegates.execute(del);
        } else if (stmt instanceof CallStmt call) {
            executeCall(call);
        } else if (stmt instanceof HandoffStmt handoff) {
            ReActAgent agent = activeAgents.get(handoff.getTargetAgent());
            AgentDef agentDef = script.getAgents().stream()
                .filter(a -> a.getName().equals(handoff.getTargetAgent()))
                .findFirst().orElse(null);
                
            String resolvedPayload = resolvePayload(handoff.getPayload());
            log.info("Handoff to " + handoff.getTargetAgent() + " (Terminal node reached). Payload: " + resolvedPayload);

            boolean replayed = journal.get(step.get()).isPresent();
            if (agent != null && agentDef != null && !replayed) {
                String contextBriefing = memoryEngine.assembleContext(agentDef, resolvedPayload, view());
                trace(TraceEvent.DELEGATE_START, handoff.getTargetAgent(), resolvedPayload, new java.util.LinkedHashMap<>(Map.of("variable", "handoff")));
                io.github.llm4j.agent.AgentResult result = limits.runUnderLimits(agent, contextBriefing);
                trace(TraceEvent.DELEGATE_END, handoff.getTargetAgent(), result.getFinalAnswer(), DelegateRunner.usageData(result));
                
                memoryEngine.storeOutcome(agentDef, resolvedPayload, result, context);
                
                auditLogger.logAgentDecision(AuditEvent.builder()
                    .sessionId(sessionId)
                    .agentResult(result)
                    .addMetadata("agent", handoff.getTargetAgent())
                    .addMetadata("statement", "handoff")
                    .timestamp(Instant.now())
                    .build());
                journal.put(step.get(), new RunJournal.Entry("handoff", result.getFinalAnswer()));
            }
            // Signal end-of-branch – no further statements should execute.
            throw new HandoffSignal("Handoff to " + handoff.getTargetAgent() + " completed.");
        } else if (stmt instanceof AltStmt alt) {
            boolean condition = ConditionEvaluator.evaluate(alt.getCondition(), view());
            log.info("Evaluating AltStmt condition [" + alt.getCondition() + "] -> " + condition);
            List<Statement> branch = condition ? alt.getIfBranch() : alt.getElseBranch();
            if (branch != null) {
                runBlock(branch, condition ? "a" : "b");
            }
        } else if (stmt instanceof BroadcastStmt broadcast) {
            String resolvedPayload = resolvePayload(broadcast.getPayload());
            log.info("Broadcasting to " + broadcast.getTargetAgents() + ": " + resolvedPayload);

            String stepId = step.get();
            var recorded = journal.get(stepId);
            String results;
            if (recorded.isPresent()) {
                results = String.valueOf(recorded.get().value());
            } else {
                List<io.github.llm4j.budget.Budget> outerScopes = scopes.get();
                if (broadcast.getBudget() != null && budgets.budgeting()) {
                    scopes.set(BudgetCoordinator.plus(outerScopes, budgets.newBudget("step " + stepId, broadcast.getBudget())));
                }
                try {
                    ThreadScope.Snapshot scope = threadScope.capture().at(stepId, locals.get());
                    List<CompletableFuture<String>> answers = new java.util.ArrayList<>();
                    for (String agentName : broadcast.getTargetAgents()) {
                        ReActAgent broadcastAgent = activeAgents.get(agentName);
                        if (broadcastAgent == null) throw new IllegalStateException("Agent not found: " + agentName);
                        answers.add(CompletableFuture.supplyAsync(() -> {
                            try (ThreadScope.Installed ignored = scope.install()) {
                                // each target is a step of its own to anyone watching: it starts and ends like a delegate, with its tokens
                                trace(TraceEvent.DELEGATE_START, agentName, resolvedPayload, new java.util.LinkedHashMap<>(Map.of("variable", broadcast.getVariableName())));
                                io.github.llm4j.agent.AgentResult r = limits.runUnderLimits(broadcastAgent, resolvedPayload);
                                trace(TraceEvent.DELEGATE_END, agentName, r.getFinalAnswer(), DelegateRunner.usageData(r));
                                if (r.budgetExhausted()) {
                                    budgets.markExhausted();
                                    if (r.getUsage().getLlmCalls() == 0) throw r.getBudgetExceeded();
                                }
                                return r.getFinalAnswer();
                            }
                        }, BRANCHES));
                    }
                    joinAll(answers);
                    results = answers.stream().map(CompletableFuture::join).toList().toString();
                } finally {
                    scopes.set(outerScopes);
                }
                journal.put(stepId, new RunJournal.Entry("broadcast", results));
            }

            // Store as a JSON-like array string
            context.setVariable(broadcast.getVariableName(), results);

            auditLogger.logConversationEvent(sessionId, null, "BROADCAST", Map.of(
                "agents",  broadcast.getTargetAgents().toString(),
                "payload", resolvedPayload
            ));
        } else if (stmt instanceof LoopStmt loop) {
            log.info("Entering loop. Condition: " + loop.getCondition());
            int rounds = 0;
            String exhaustedBy = null;
            io.github.llm4j.budget.BudgetExceeded refusal = null;
            io.github.llm4j.budget.Budget loopBudget = loop.getBudget() != null && budgets.budgeting()
                    ? budgets.newBudget("loop " + step.get(), loop.getBudget()) : null;
            List<io.github.llm4j.budget.Budget> outerScopes = scopes.get();
            if (loopBudget != null) scopes.set(BudgetCoordinator.plus(outerScopes, loopBudget));
            try {
                while (!ConditionEvaluator.evaluate(loop.getCondition(), view())) {
                    if (loop.getMaxIterations() > 0 && rounds >= loop.getMaxIterations()) {
                        exhaustedBy = "rounds";
                        break;
                    }
                    // A budget that already refused can't pay for another round (its steps may have
                    // handled the refusal in on_failure): stop instead of spinning.
                    if (budgets.anyRefused(loopBudget)) {
                        exhaustedBy = "budget";
                        break;
                    }
                    rounds++;
                    context.setVariable("_loopRound", String.valueOf(rounds));
                    try {
                        runBlock(loop.getBody(), "r" + rounds + ".");
                    } catch (io.github.llm4j.budget.BudgetExceeded e) {
                        if (loopBudget == null || !e.budget().equals(loopBudget.name())) throw e;
                        refusal = e;
                        rounds--; // that round couldn't be paid for
                        exhaustedBy = "budget";
                        break;
                    }
                }
            } finally {
                scopes.set(outerScopes);
            }
            if (exhaustedBy != null) {
                log.warning("Loop stopped (" + exhaustedBy + ") after " + rounds + " round(s) without: " + loop.getCondition());
                context.setVariable("_loopRounds", String.valueOf(rounds));
                context.setVariable("_loopExhaustedBy", exhaustedBy);
                if (!loop.getOnExhausted().isEmpty()) {
                    runBlock(loop.getOnExhausted(), "x");
                } else if (refusal != null) {
                    throw refusal;
                }
            }
            log.info("Exiting loop.");
        } else if (stmt instanceof HumanPromptStmt hp) {
            String resolvedMessage = resolvePayload(hp.getMessage());
            log.info("Human Prompt: " + resolvedMessage);
            String stepId = step.get();
            String recordedAnswer = rewinder.recordedAnswer(resolvedMessage);
            if (recordedAnswer != null) {
                context.setVariable(hp.getVariableName(), recordedAnswer);
            } else if (humanInterface != null) {
                // May return now, or throw RunSuspended to wait without holding this thread.
                String result = humanInterface.promptHuman(stepId, resolvedMessage, HumanInterface.Hints.none());
                rewinder.recordAnswer(resolvedMessage, result);
                context.setVariable(hp.getVariableName(), result);
            } else {
                log.severe("HumanInterface not configured – human_prompt cannot be served. " +
                           "Call setHumanInterface() before executing this workflow.");
                throw new IllegalStateException("HumanInterface is required for human_prompt but was not set.");
            }
        } else if (stmt instanceof GuardrailStmt guard) {
            log.info("Executing guardrail: " + guard.getType());
            if ("PII".equalsIgnoreCase(guard.getType())) {
                // Pre-execution scan (e.g. check variables in context)
                boolean violation = false;
                for (Object valObj : context.getAll().values()) {
                    String val = String.valueOf(valObj);
                    if (piiDetector.detect(val).containsPII()) {
                        violation = true;
                        break;
                    }
                }
                
                if (violation) {
                    log.warning("PII violation detected! Executing on_violation branch.");
                    runBlock(guard.getOnViolation(), "v");
                } else {
                    runBlock(guard.getBody(), "g");
                }
            } else {
                // Default: just execute body
                runBlock(guard.getBody(), "g");
            }
        } else if (stmt instanceof ParallelStmt parallel) {
            log.info("Entering parallel block with " + parallel.getBody().size() + " branches.");
            String parent = step.get();
            Map<String, Object> names = locals.get();
            List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < parallel.getBody().size(); i++) {
                Statement pStmt = parallel.getBody().get(i);
                futures.add(forkWith(parent + "/p" + i, names, () -> executeStatement(pStmt)));
            }
            joinAll(futures);
            log.info("Parallel block completed.");
        } else if (stmt instanceof ForEachStmt each) {
            executeForEach(each);
        } else if (stmt instanceof ObserveStmt obs) {
            String resolvedLabel = resolvePayload(obs.getLabel());
            String resolvedExpr = resolvePayload(obs.getExpression());
            log.info("OBSERVE [" + resolvedLabel + "]: " + resolvedExpr);
            
            auditLogger.logConversationEvent(sessionId, null, "OBSERVATION", Map.of(
                "label", resolvedLabel,
                "value", resolvedExpr
            ));
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private Duration parseDuration(String raw) {
        if (raw == null || raw.isEmpty()) return Duration.ZERO;
        String val = raw.toLowerCase().trim();
        if (val.endsWith("s")) return Duration.ofSeconds(Long.parseLong(val.substring(0, val.length()-1)));
        if (val.endsWith("m")) return Duration.ofMinutes(Long.parseLong(val.substring(0, val.length()-1)));
        if (val.endsWith("h")) return Duration.ofHours(Long.parseLong(val.substring(0, val.length()-1)));
        return Duration.ofSeconds(Long.parseLong(val));
    }

    /**
     * Resolves variable references in a payload string.
     *
     * <p>Variables are referenced using curly-brace delimiters: {@code {varName}}.
     * This prevents substring collisions that would occur with bare name substitution
     * (e.g., {@code id} being substituted inside {@code child_id}).
     *
     * <p><b>Legacy support</b>: bare variable names (no braces) that exactly match
     * a context key are also substituted for backward compatibility with existing
     * {@code .loom} scripts.
     *
     * @param rawPayload the raw payload string from the AST node
     * @return the payload with all resolvable variable references replaced
     */
    private void executeForEach(ForEachStmt each) {
        Object collection = ConditionEvaluator.resolvePath(each.getCollectionPath(), view());
        List<?> items = collection instanceof List<?> l ? l : List.of();
        if (!(collection instanceof List<?>) && collection != null && !String.valueOf(collection).isBlank()) {
            log.warning("for each: " + each.getCollectionPath() + " is not a list — skipping");
        }
        log.info("for each " + each.getItemName() + " in " + each.getCollectionPath() + ": " + items.size() + " item(s)"
                + (each.isParallel() ? " in parallel" : ""));
        String parent = step.get();
        Map<String, Object> outer = locals.get();
        io.github.llm4j.budget.Budget eachBudget = each.getBudget() != null && budgets.budgeting()
                ? budgets.newBudget("for each " + parent, each.getBudget()) : null;
        List<io.github.llm4j.budget.Budget> outerScopes = scopes.get();
        if (eachBudget != null) scopes.set(BudgetCoordinator.plus(outerScopes, eachBudget));
        io.github.llm4j.budget.BudgetExceeded refusal = null;
        boolean stopped = false;
        try {
            refusal = runItems(each, items, parent, outer, eachBudget);
        } catch (io.github.llm4j.budget.BudgetExceeded e) {
            if (eachBudget == null || !e.budget().equals(eachBudget.name())) throw e;
            refusal = e;
        } finally {
            scopes.set(outerScopes);
        }
        stopped = refusal != null || (eachBudget != null && eachBudget.refused());
        if (stopped) {
            log.warning("for each " + each.getItemName() + " stopped: its budget ran out");
            context.setVariable("_loopExhaustedBy", "budget");
            if (!each.getOnExhausted().isEmpty()) {
                runBlock(each.getOnExhausted(), "x");
            } else if (refusal != null) {
                throw refusal;
            }
        }
    }

    /** Runs each item (in parallel or in turn); returns this for-each's own refusal if it stopped the items. */
    private io.github.llm4j.budget.BudgetExceeded runItems(ForEachStmt each, List<?> items, String parent,
                                                          Map<String, Object> outer, io.github.llm4j.budget.Budget eachBudget) {
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!each.isParallel() && budgets.anyRefused(eachBudget)) break;
            Map<String, Object> names = new HashMap<>(outer);
            names.put(each.getItemName(), items.get(i));
            names.put("_index", String.valueOf(i));
            String itemStep = parent + "/e" + i;
            Runnable body = () -> runBlock(each.getBody(), "b");
            if (each.isParallel()) {
                futures.add(forkWith(itemStep, names, body));
            } else {
                step.set(itemStep);
                locals.set(names);
                try {
                    body.run();
                } catch (io.github.llm4j.budget.BudgetExceeded e) {
                    if (eachBudget == null || !e.budget().equals(eachBudget.name())) throw e;
                    return e;
                } finally {
                    step.set(parent);
                    locals.set(outer);
                }
            }
        }
        joinAll(futures);
        return null;
    }

    /** A name written {@code {item.field}} is resolved now; a plain name is used as written. */
    private String resolveName(String name) {
        if (!name.startsWith("{")) return name;
        Object value = ConditionEvaluator.resolvePath(name.substring(1, name.length() - 1), view());
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("Could not resolve " + name + " to a name");
        }
        return String.valueOf(value);
    }

    /** Runs an {@code on_failure} block with {@code _error} in scope. */
    void runFailureHandler(List<Statement> onFailure, String message) {
        Map<String, Object> outer = locals.get();
        Map<String, Object> names = new HashMap<>(outer);
        names.put("_error", message);
        locals.set(names);
        try {
            runBlock(onFailure, "f");
        } finally {
            locals.set(outer);
        }
    }

    String resolveVariableName(String name) { return resolveName(name); }

    /** Runs {@code work} on another thread, with this thread's step id and names, and gives up after {@code timeoutMillis}. */
    <T> T callWithTimeout(java.util.concurrent.Callable<T> work, long timeoutMillis, String what) throws Exception {
        ThreadScope.Snapshot scope = threadScope.capture();
        // submit() (not a CompletableFuture): cancel(true) on its Future interrupts the thread that is running the work
        java.util.concurrent.Future<T> future = BRANCHES.submit(() -> {
            try (ThreadScope.Installed ignored = scope.install()) {
                return work.call();
            }
        });
        try {
            return future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new java.util.concurrent.TimeoutException(what + " timed out after " + timeoutMillis + " ms");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private final java.util.concurrent.atomic.AtomicReference<Map<String, Object>> unhandledFailure = new java.util.concurrent.atomic.AtomicReference<>();

    /** The category a failure belongs to: the given one, or the one the exception says (the same words a tool failure uses). */
    io.github.llm4j.telemetry.ErrorCategory failureCategory(Throwable cause, io.github.llm4j.telemetry.ErrorCategory given) {
        if (given != null) return given;
        return cause == null ? io.github.llm4j.telemetry.ErrorCategory.OTHER : io.github.llm4j.telemetry.ErrorCategory.of(cause);
    }

    /** Tells the trace a task or delegate failed, and remembers it for {@code run_end} when nothing handles it. */
    void traceFailure(String kind, String name, String message, Throwable cause, io.github.llm4j.telemetry.ErrorCategory given, int attempts, boolean handled) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("kind", kind);
        d.put("name", name);
        d.put("step", currentStep());
        d.put("category", failureCategory(cause, given).name());
        if (cause != null) d.put("error_type", cause.getClass().getSimpleName());
        d.put("attempts", attempts);
        d.put("handled", handled);
        d.put("error", message == null ? "" : message);
        if (!handled) unhandledFailure.set(d);
        trace(TraceEvent.FAILURE, null, kind + " " + name + (handled ? " failed; on_failure took over" : " failed"), d);
    }

    // ── Budgets ─────────────────────────────────────────────────────────────────────────────

    private List<String> modelsOf(AgentDef agentDef) {
        if (agentDef.getRoutingPolicy() != null) {
            for (RoutingPolicyDef policy : script.getRoutingPolicies()) {
                if (policy.getName().equals(agentDef.getRoutingPolicy())) {
                    List<String> models = new java.util.ArrayList<>();
                    models.add(policy.getPrimaryModel());
                    models.addAll(policy.getFallbackModels());
                    return models;
                }
            }
        }
        return List.of(String.valueOf(agentDef.getModel()));
    }

    /**
     * The client a task gets from {@code ctx.llm()}: the model the {@code run} statement named, metered like an agent's. Its spend is
     * reported under {@code task <Name>}, and counts against the run budget and the budgets around the statement.
     */
    io.github.llm4j.LLMClient clientForTask(String taskName, String model) {
        return budgets.metered(clientFor(model), "task " + taskName, null, model);
    }

    // ── Limits: pause, wait or fail ─────────────────────────────────────────────────────────

    public static String human(Duration d) {
        return LimitPolicy.human(d);
    }

    /** Start of a top-level run: counts a resume after a pause, and publishes {@code _run}. */
    private void beginRun() {
        unhandledFailure.set(null);
        Map<String, Object> last = journal.get(SUSPENSION)
                .map(e -> e.value() instanceof Map<?, ?> m ? new java.util.LinkedHashMap<>(Vals.castMap(m)) : null)
                .orElse(null);
        resumes = last == null ? 0 : (int) Vals.number(last.get("resumes"));
        resumedFromPause = last != null && "suspended".equals(last.get("state"));
        if (last != null && "suspended".equals(last.get("state"))) {
            // A sleep is the script's own choice, not a limit: waking from one is not counted against max_resumes.
            boolean slept = "SLEEP".equals(String.valueOf(last.get("reason")));
            if (!slept) resumes++;
            Instant now = clock.instant();
            last.put("resumes", resumes);
            if (!slept && resumes > limits.maxResumes()) {
                last.put("state", "failed");
                journal.put(SUSPENSION, new RunJournal.Entry("suspension", last));
                throw new IllegalStateException("Run paused for limits and resumed " + (resumes - 1)
                        + " times already (max_resumes " + limits.maxResumes() + "); giving up. Last limit: " + last.get("detail"));
            }
            last.put("state", "resumed");
            last.put("resumedAt", now.toString());
            journal.put(SUSPENSION, new RunJournal.Entry("suspension", last));
            long waited = 0;
            try {
                waited = Duration.between(Instant.parse(String.valueOf(last.get("suspendedAt"))), now).toMillis();
            } catch (RuntimeException ignored) {
                // an old or hand-written record
            }
            log.info("Resuming run (resume " + resumes + ") after pausing at " + last.get("step") + " for " + last.get("reason"));
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("attempt", String.valueOf(resumes));
            data.put("waitedMillis", String.valueOf(waited));
            data.put("step", String.valueOf(last.get("step")));
            auditLogger.logConversationEvent(sessionId, null, "run_resumed", data);
        }
        // Published only once a run has paused: older scripts may use "_run" inside words (bare-name
        // substitution would rewrite them).
        if (last != null) {
            Map<String, Object> run = new java.util.LinkedHashMap<>();
            run.put("resumes", resumes);
            run.put("lastSuspension", last);
            context.setVariable("_run", run);
        }
    }

    /** The run paused for a limit: journal why and until when, tell listeners (e.g. a trigger store). */
    private void recordSuspension(RunSuspended paused) {
        Map<String, Object> rec = new java.util.LinkedHashMap<>();
        rec.put("state", "suspended");
        rec.put("step", paused.stepId());
        rec.put("reason", paused.reason().name());
        rec.put("resumeAt", paused.resumeAt().toString());
        rec.put("suspendedAt", clock.instant().toString());
        rec.put("resumes", resumes);
        if (paused.limit() != null) {
            rec.putAll(LimitPolicy.limitData(paused.limit()));
            rec.put("detail", paused.limit().describe());
        } else if (paused.detail() != null) {
            rec.put("detail", paused.detail());
        }
        journal.put(SUSPENSION, new RunJournal.Entry("suspension", rec));
        if (triggerStore != null && runId != null) {
            Duration wait = Duration.between(clock.instant(), paused.resumeAt());
            Instant at = paused.resumeAt().plus(io.github.llm4j.loom.trigger.Schedules.jitter(wait.isNegative() ? Duration.ZERO : wait));
            triggerStore.upsert(io.github.llm4j.loom.trigger.Trigger.resume(runId, at, String.valueOf(rec.get("detail")), resumes + 1));
            rec.put("trigger", io.github.llm4j.loom.trigger.Trigger.resumeId(runId));
        }
        log.warning("Run paused at " + paused.stepId() + " (" + rec.get("detail") + "); resumes at " + paused.resumeAt());
        auditLogger.logConversationEvent(sessionId, null, "run_suspended", new java.util.LinkedHashMap<>(rec));
        trace(TraceEvent.SUSPENDED, null, "paused at " + paused.stepId() + " (" + rec.get("detail") + "); resumes at " + paused.resumeAt(), null);
        for (java.util.function.Consumer<RunSuspended> l : suspensionListeners) {
            try {
                l.accept(paused);
            } catch (RuntimeException e) {
                log.warning("Suspension listener failed: " + e.getMessage());
            }
        }
    }

    private void executeCall(CallStmt call) {
        log.info("Calling sub-workflow: " + call.getWorkflowName());
        
        VariableContext original = this.context;
        try {
            VariableContext subContext = original.pushFrame();
            for (Map.Entry<String, String> arg : call.getArguments().entrySet()) {
                subContext.setVariable(arg.getKey(), resolvePayload(arg.getValue()));
            }
            
            this.setInternalContext(subContext);
            executeWorkflow(call.getWorkflowName(), null);
            
            Object resultValue = subContext.getVariable("result");
            original.setVariable(call.getResultVariable(), resultValue);
        } finally {
            this.setInternalContext(original);
        }
    }

    private void setInternalContext(VariableContext context) {
        try {
            java.lang.reflect.Field field = HarnessExecutor.class.getDeclaredField("context");
            field.setAccessible(true);
            field.set(this, context);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }



    /**
     * Hook called for every agent just before it is built, after its model, prompt and tools are
     * configured. Embedders can attach listeners, approval callbacks, iteration limits, etc.
     */
    protected void customizeAgent(AgentDef agentDef, ReActAgent.Builder builder) {
        // No-op by default.
    }

    /**
     * Hook called on every delegate attempt to choose the agent instance that runs it. Returning a
     * rebuilt agent (e.g. {@code agent.toBuilder().systemPrompt(null).instructions(prompt).build()})
     * lets embedders inject prompts generated at runtime — by an orchestrator agent, say — while
     * keeping the agent's tools, listeners and approval callback.
     */
    protected ReActAgent agentForDelegate(DelegateStmt stmt, AgentDef agentDef, ReActAgent agent) {
        return agent;
    }

    /**
     * Hook called after context assembly and before delegate execution.
     * Implementors can inject additional context or enforce budgeting.
     */
    protected String beforeDelegateExecution(DelegateStmt stmt, AgentDef agentDef, String resolvedPayload, String assembledContext) {
        return assembledContext;
    }

    /**
     * Hook called on successful delegate execution.
     * Implementors must call {@code successHandler.onSuccess(...)} to persist result and audit data.
     */
    protected void afterDelegateExecution(
            DelegateStmt stmt,
            AgentDef agentDef,
            String resolvedPayload,
            String effectiveContext,
            io.github.llm4j.agent.AgentResult result,
            Object finalValue,
            DelegateSuccessHandler successHandler
    ) {
        successHandler.onSuccess(result, finalValue);
    }

    /**
     * Hook called for each failed attempt before retry or failure handling.
     */
    protected void onDelegateError(
            DelegateStmt stmt,
            AgentDef agentDef,
            String resolvedPayload,
            String effectiveContext,
            Exception error,
            int attemptNumber,
            int maxAttempts
    ) {
        // No-op by default.
    }
}
