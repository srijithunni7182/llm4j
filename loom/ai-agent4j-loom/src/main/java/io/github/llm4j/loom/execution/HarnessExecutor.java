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
    private PromptRegistry promptRegistry;
    private final String sessionId = UUID.randomUUID().toString();
    private MemoryEngine memoryEngine = new TranscriptAccumulationEngine();

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
    /** The id of the step this thread is executing: its position in the script (stable across runs). */
    private final ThreadLocal<String> step = ThreadLocal.withInitial(() -> "");
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
    /** Block-local names (a for-each item, _error) visible to the statements of that block. */
    private final ThreadLocal<Map<String, Object>> locals = ThreadLocal.withInitial(Map::of);

    // ── Budgets ──────────────────────────────────────────────────────────
    /** True when the script (or an override) declares any budget; only then are clients metered. */
    private boolean budgeting;
    private io.github.llm4j.budget.Budget runBudget;
    private final Map<String, io.github.llm4j.budget.Budget> agentBudgets = new HashMap<>();
    /** Step, loop and for-each budgets enclosing the statement this thread is running. */
    private final ThreadLocal<List<io.github.llm4j.budget.Budget>> scopes = ThreadLocal.withInitial(List::of);
    private io.github.llm4j.budget.PriceTable priceTable;
    private io.github.llm4j.budget.TokenEstimator tokenEstimator;
    private Long overrideTokens;
    private Long overrideCalls;
    private java.math.BigDecimal overrideCost;
    private final java.util.concurrent.atomic.AtomicBoolean budgetExhausted = new java.util.concurrent.atomic.AtomicBoolean();
    private final List<SpendReport.Line> spendLines = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Journal key prefix for a step's usage: {@code <step>#usage:<agent>}. */
    static final String USAGE = "#usage:";
    private boolean spendRestored;
    /** Per budget name: what happens when it runs out, and its limits as written (for "ask" top-ups). */
    private final Map<String, BudgetDef.WhenExhausted> budgetPolicies = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, io.github.llm4j.budget.Budget> budgetsByName = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, io.github.llm4j.budget.Limits> originalLimits = new java.util.concurrent.ConcurrentHashMap<>();

    // ── Pausing and resuming ─────────────────────────────────────────────
    /** Journal key of the run's latest pause for a limit. */
    public static final String SUSPENSION = "#suspension";
    static final int DEFAULT_MAX_RESUMES = 50;
    static final Duration DEFAULT_MAX_INLINE_WAIT = Duration.ofMinutes(5);
    static final Duration DEFAULT_MAX_SUSPEND = Duration.ofDays(7);
    private java.time.Clock clock = java.time.Clock.systemUTC();
    private io.github.llm4j.ratelimit.Sleeper sleeper = io.github.llm4j.ratelimit.Sleeper.SYSTEM;
    private int resumes;
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
    private final List<java.util.function.Consumer<RunSuspended>> suspensionListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<TraceListener> traceListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    
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

    public void setHumanInterface(HumanInterface humanInterface) { this.humanInterface = humanInterface; }
    public void setPromptRegistry(PromptRegistry promptRegistry) { this.promptRegistry = promptRegistry; }
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
    /** True when a budget has refused a call or has nothing left: a rewind would only spend what isn't there. */
    boolean overBudget() { return budgeting && (anyRefused(null) || (runBudget != null && runBudget.exhausted())); }
    // ---- what the Decider needs ---------------------------------------------------------------------------------------

    LoomScript script() { return script; }
    Path baseDir() { return baseDir; }
    private final Decider decider = new Decider(this);
    private io.github.llm4j.loom.autonomy.Ledger ledger;
    private io.github.llm4j.loom.autonomy.LevelStore levelStore;
    private Replay replay;

    /** Where the ledger and the levels of decisions are kept; without them a decision runs at its start level and writes nothing. */
    public void setAutonomy(io.github.llm4j.loom.autonomy.Ledger ledger, io.github.llm4j.loom.autonomy.LevelStore levels) {
        this.ledger = ledger;
        this.levelStore = levels;
    }

    io.github.llm4j.loom.autonomy.Ledger ledger() { return ledger; }
    io.github.llm4j.loom.autonomy.LevelStore levelStore() { return levelStore; }
    Replay replay() { return replay; }

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
        atStep(stepId, () -> executeDelegate(del));
    }

    Decider decider() { return decider; }
    String envValue(String name) {
        try {
            return envLookup.apply(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The level an agent that replaced another may start at: the replay of the old agent's cases under the new one (see {@link Decider}). */
    io.github.llm4j.loom.autonomy.Level inheritedLevel(io.github.llm4j.loom.ast.DecisionDef def, String scope, io.github.llm4j.loom.autonomy.LevelState old, String identity) {
        return inheritance != null ? inheritance.inherited(def, scope, old, identity) : def.getStartAt();
    }

    /** What runs the replay when an agent changes with {@code test it on past cases}; without it a new agent starts over. */
    public interface Inheritance {
        io.github.llm4j.loom.autonomy.Level inherited(io.github.llm4j.loom.ast.DecisionDef def, String scope, io.github.llm4j.loom.autonomy.LevelState old, String identity);
    }

    private Inheritance inheritance;

    public void setInheritance(Inheritance inheritance) { this.inheritance = inheritance; }

    String recordedAnswer(String question) { return rewinder.recordedAnswer(question); }
    void recordAnswer(String question, String answer) { rewinder.recordAnswer(question, answer); }

    /** A tool by its declared name, as an agent would be given it (journaled effects and all). */
    Tool toolNamed(String name) { return resolveTool(name); }

    String forStorage(String agentName, String text) {
        AgentGuard guard = guards.get(agentName);
        return guard == null ? text : guard.forStorage(text);
    }

    private String stopAt;
    /** Ends the run cleanly, as {@link io.github.llm4j.loom.runtime.RunStopped}, once the named step or checkpoint has completed. */
    public void setStopAt(String point) { this.stopAt = point; }
    /** A place a run can be told to stop at: a step id (with or without its attempt) or a checkpoint's name. */
    void stopPoint(String point) {
        if (stopAt != null && (stopAt.equals(point) || stopAt.equals(Generations.strip(point)))) throw new io.github.llm4j.loom.runtime.RunStopped(point);
    }
    public void setSimulate(boolean simulate) { this.simulate = simulate; }
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
        traceListeners.add(listener);
    }

    boolean tracing() {
        return !traceListeners.isEmpty();
    }

    /** Tells trace listeners; free when nobody listens. */
    void trace(String type, String agent, String text, Map<String, Object> data) {
        if (traceListeners.isEmpty()) return;
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (data != null) data.forEach((k, v) -> { if (k != null && v != null) copy.put(k, v); });
        TraceEvent event = new TraceEvent(type, agent, step.get(), text == null ? "" : text,
                java.util.Collections.unmodifiableMap(copy), clock.instant());
        Runnable deliver = () -> {
            for (TraceListener l : traceListeners) {
                try {
                    l.onEvent(event);
                } catch (RuntimeException e) {
                    log.warning("Trace listener failed: " + e.getMessage());
                }
            }
        };
        if (!decider.hold(deliver)) deliver.run();
    }

    /** Forwards one agent's own events (thoughts, tool calls, observations, spend) to the trace. */
    private io.github.llm4j.agent.AgentEventListener traceAdapter(String agentName) {
        return new io.github.llm4j.agent.AgentEventListener() {
            @Override
            public void onThought(String thought) {
                trace(TraceEvent.THOUGHT, agentName, thought, null);
            }

            @Override
            public void onAction(String toolName, String toolInput) {
                trace(TraceEvent.ACTION, agentName, toolName + " " + (toolInput == null ? "" : toolInput),
                        Map.of("tool", toolName));
            }

            @Override
            public void onObservation(String observation) {
                trace(TraceEvent.OBSERVATION, agentName, observation, null);
            }

            @Override
            public void onBudget(io.github.llm4j.budget.BudgetEvent event) {
                trace(TraceEvent.BUDGET, agentName, event.kind() + " " + event.budget() + ": spent " + event.spent()
                        + " of " + event.limits(), Map.of("budget", event.budget(), "kind", String.valueOf(event.kind())));
            }
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
        return runUnderLimits(agent, task);
    }

    /**
     * Lenient mode: features the runtime doesn't support yet (agent memory, unknown guardrail types) are
     * warnings instead of load errors. Unknown names and missing secrets are always errors.
     */
    public void setLenient(boolean lenient) {
        this.lenient = lenient;
    }

    /** Where {@code env.NAME} values come from (default: the process environment). */
    public void setEnvLookup(java.util.function.Function<String, String> envLookup) {
        this.envLookup = envLookup != null ? envLookup : System::getenv;
    }

    /** How knowledge bases embed text (default: gemini/…, and onnx/… or djl/… with the addons module). */
    public void setEmbeddingFactory(io.github.llm4j.loom.knowledge.EmbeddingFactory factory) {
        this.embeddingFactory = factory;
    }

    private io.github.llm4j.loom.knowledge.EmbeddingFactory embeddings() {
        if (embeddingFactory == null) embeddingFactory = new io.github.llm4j.loom.knowledge.DefaultEmbeddingFactory(envLookup);
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
                .env(envLookup)
                .lenient(lenient)
                .humanInterface(humanInterface != null)
                .baseDir(baseDir)
                .templates(promptRegistry == null ? null : id -> promptRegistry.get(id).isPresent())
                .check(this::checkAgentSettings)
                .check(this::checkToolsAndApprovals)
                .check(this::checkKnowledge)
                .check(this::checkProviders);
    }

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
        String key = option(declared, "api_key");
        return llmClientFactory.createClient(new ProviderSpec(declared.getName(), declared.getKind(), baseUrl, key),
                model.substring(declared.getName().length() + 1));
    }

    private String option(io.github.llm4j.loom.ast.ToolDef def, String name) {
        io.github.llm4j.loom.ast.ToolDef.OptionValue v = def.getOptions().get(name);
        if (v == null) return null;
        return v.fromEnv() ? envLookup.apply(v.value()) : v.value();
    }

    private void checkProviders(ScriptValidator.Checker c) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (io.github.llm4j.loom.ast.ProviderDef p : script.getProviders()) {
            String who = "provider " + p.getName();
            if (!seen.add(p.getName())) c.error(p.getLine(), who, "declared twice");
            if (ProviderSpec.KINDS.contains(p.getName().toLowerCase(java.util.Locale.ROOT))) {
                c.error(p.getLine(), who, "the name " + p.getName() + " is reserved for the built-in " + p.getName().toLowerCase(java.util.Locale.ROOT)
                        + "/… models; pick another name");
            }
            if (!ProviderSpec.KINDS.contains(p.getKind())) {
                c.error(p.getLine(), who, "unknown use: " + p.getKind() + "; use one of gemini, anthropic, ollama, sarvam");
                continue;
            }
            for (var e : p.getOptions().entrySet()) {
                switch (e.getKey()) {
                    case "base_url" -> { }
                    case "api_key" -> {
                        if (!e.getValue().fromEnv()) {
                            c.error(p.getLine(), who, "api_key must come from the environment, e.g. api_key: env.MY_KEY");
                        } else if (envLookup.apply(e.getValue().value()) == null || envLookup.apply(e.getValue().value()).isBlank()) {
                            c.error(p.getLine(), who, "environment variable " + e.getValue().value() + " is not set");
                        }
                    }
                    default -> c.error(p.getLine(), who, "unknown option " + e.getKey() + "; use base_url or api_key");
                }
            }
            if (!p.getKind().equals("ollama") && !p.getOptions().containsKey("api_key")) {
                c.error(p.getLine(), who, p.getKind() + " needs api_key: env.<NAME>");
            }
        }
        for (AgentDef a : script.getAgents()) {
            if (a.getRoutingPolicy() == null) {
                if (a.getModel() == null) c.error(a.getLine(), "agent " + a.getName(), "needs model: \"…\" or routing: <policy>");
                else checkModel(c, a.getLine(), "agent " + a.getName(), a.getModel());
            }
        }
        for (RoutingPolicyDef r : script.getRoutingPolicies()) {
            if (r.getPrimaryModel() != null) checkModel(c, r.getLine(), "routing " + r.getName(), r.getPrimaryModel());
            for (String fb : r.getFallbackModels()) checkModel(c, r.getLine(), "routing " + r.getName(), fb);
        }
    }

    private void checkModel(ScriptValidator.Checker c, int line, String who, String model) {
        if (declaredProvider(model) != null) {
            if (model.substring(model.indexOf('/') + 1).isBlank()) c.error(line, who, "model " + model + " names no model after the provider");
            return;
        }
        String problem = llmClientFactory.problem(model);
        if (problem != null) c.error(line, who, problem);
    }

    // ── Agent memory, voice and guard settings ──────────────────────────────────────────────

    private static final java.util.Set<String> MEMORY_KEYS = java.util.Set.of(
            "conversation", "limit", "session", "facts", "embedding", "recall", "min_similarity");
    private static final java.util.Set<String> VOICE_KEYS = java.util.Set.of("listen", "speak", "language", "voice", "out");
    private static final java.util.Set<String> GUARD_KEYS = java.util.Set.of("pii", "bias", "bias_model");

    private void checkAgentSettings(ScriptValidator.Checker c) {
        for (AgentDef a : script.getAgents()) {
            String who = "agent " + a.getName();
            if (a.getMemory() != null) checkMemory(c, who, a.getMemory());
            if (a.getVoice() != null) checkVoice(c, who, a.getVoice());
            if (a.getGuard() != null) checkGuard(c, who, a.getGuard());
        }
    }

    /** Unknown keys and env references (these settings are written in the script, not secrets). */
    private static void checkKeys(ScriptValidator.Checker c, String who, String block,
                                  io.github.llm4j.loom.ast.Settings settings, java.util.Set<String> allowed) {
        for (var e : settings.getValues().entrySet()) {
            if (!allowed.contains(e.getKey())) {
                if (block.equals("memory") && (e.getKey().equals("type") || e.getKey().equals("path"))) {
                    c.error(settings.lineOf(e.getKey()), who, "memory " + e.getKey() + ": is no longer used; write "
                            + "conversation: \"<directory>\" (or memory) for conversations, and facts: \"<file>\" with embedding: \"…\" for long-term facts");
                } else {
                    c.error(settings.lineOf(e.getKey()), who, "unknown " + block + " setting " + e.getKey()
                            + "; use " + new java.util.TreeSet<>(allowed));
                }
            } else if (e.getValue().fromEnv()) {
                c.error(settings.lineOf(e.getKey()), who, block + " " + e.getKey() + " is written in the script, not taken from the environment");
            }
        }
    }

    private void checkMemory(ScriptValidator.Checker c, String who, AgentDef.MemoryConfig m) {
        checkKeys(c, who, "memory", m, MEMORY_KEYS);
        if (!m.has("conversation") && !m.has("facts")) {
            c.error(m.getLine(), who, "memory needs conversation: \"<directory>\" | memory, or facts: \"<file>\" | memory (or both)");
        }
        wholeNumber(c, who, m, "limit");
        wholeNumber(c, who, m, "recall");
        if (m.has("min_similarity")) {
            double v = m.getDouble("min_similarity", -1);
            if (v < 0 || v > 1) c.error(m.lineOf("min_similarity"), who, "memory min_similarity must be between 0 and 1");
        }
        String conversation = m.getConversation();
        if (conversation != null && !AgentMemory.MEMORY.equals(conversation)
                && java.nio.file.Files.isRegularFile(baseDir.resolve(conversation))) {
            c.error(m.lineOf("conversation"), who, "memory conversation: " + conversation + " is a file; give a directory");
        }
        if (m.has("facts")) {
            if (m.getEmbedding() == null) {
                c.error(m.lineOf("facts"), who, "memory facts need embedding: \"<model>\" (e.g. gemini/text-embedding-004)");
            } else {
                String problem = embeddings().problem(m.getEmbedding());
                if (problem != null) c.error(m.lineOf("embedding"), who, problem);
            }
            String facts = m.getFacts();
            if (facts != null && !AgentMemory.MEMORY.equals(facts) && java.nio.file.Files.isDirectory(baseDir.resolve(facts))) {
                c.error(m.lineOf("facts"), who, "memory facts: " + facts + " is a directory; give a file, e.g. memory/facts.json");
            }
        } else if (m.has("embedding") || m.has("recall") || m.has("min_similarity")) {
            c.warn(m.getLine(), who, "memory embedding/recall/min_similarity only apply with facts:");
        }
    }

    private static void wholeNumber(ScriptValidator.Checker c, String who, io.github.llm4j.loom.ast.Settings s, String key) {
        if (!s.has(key)) return;
        try {
            double v = Double.parseDouble(s.get(key));
            if (v < 1 || v != Math.floor(v)) throw new NumberFormatException();
        } catch (RuntimeException e) {
            c.error(s.lineOf(key), who, key + " must be a positive whole number, got " + s.getValues().get(key));
        }
    }

    private void checkVoice(ScriptValidator.Checker c, String who, AgentDef.VoiceConfig v) {
        checkKeys(c, who, "voice", v, VOICE_KEYS);
        if (!v.has("listen") && !v.has("speak")) c.error(v.getLine(), who, "voice needs listen: \"sarvam/<model>\" or speak: \"sarvam/<model>\"");
        for (String key : List.of("listen", "speak")) {
            String model = v.get(key);
            if (model == null) continue;
            if (!model.startsWith(AgentVoice.PREFIX) || model.length() == AgentVoice.PREFIX.length()) {
                c.error(v.lineOf(key), who, "voice " + key + ": " + model + " is not supported; use sarvam/<model>, e.g. "
                        + (key.equals("speak") ? "sarvam/bulbul:v2" : "sarvam/saarika:v2.5"));
            }
        }
        if ((v.has("listen") || v.has("speak")) && (envLookup.apply("SARVAM_API_KEY") == null || envLookup.apply("SARVAM_API_KEY").isBlank())) {
            c.error(v.getLine(), who, "voice needs SARVAM_API_KEY in the environment");
        }
        try {
            io.github.llm4j.tools.SafePaths.inside(baseDir, v.getOut());
        } catch (IllegalArgumentException e) {
            c.error(v.lineOf("out"), who, "voice out: " + e.getMessage());
        }
    }

    private void checkGuard(ScriptValidator.Checker c, String who, AgentDef.GuardConfig g) {
        checkKeys(c, who, "guard", g, GUARD_KEYS);
        if (!g.has("pii") && !g.has("bias")) c.error(g.getLine(), who, "guard needs pii: mask | block | warn, or bias: warn | block");
        if (g.getPii() != null && !AgentGuard.PII_VALUES.contains(g.getPii())) {
            c.error(g.lineOf("pii"), who, "guard pii: " + g.getPii() + " is not one of mask, block, warn");
        }
        if (g.getBias() != null && !AgentGuard.BIAS_VALUES.contains(g.getBias())) {
            c.error(g.lineOf("bias"), who, "guard bias: " + g.getBias() + " is not one of warn, block");
        }
        if (g.has("bias_model")) {
            if (!g.has("bias")) c.error(g.lineOf("bias_model"), who, "guard bias_model needs bias: warn | block");
            else if (g.getBiasModel() != null) checkModel(c, g.lineOf("bias_model"), who, g.getBiasModel());
        }
    }

    private void checkKnowledge(ScriptValidator.Checker c) {
        for (KnowledgeDef kb : script.getKnowledgeBases()) {
            String who = "knowledge " + kb.getName();
            if (kb.getType() != null) {
                c.warn(kb.getLine(), who, "type: is no longer used (every knowledge base is searched by meaning); remove it");
            }
            if (kb.getSource() == null) {
                c.error(kb.getLine(), who, "needs source: \"<file or directory>\"");
            } else {
                Path src = io.github.llm4j.loom.knowledge.KnowledgeIndexer.source(kb, baseDir);
                if (!java.nio.file.Files.exists(src)) {
                    c.error(kb.getLine(), who, "source " + kb.getSource() + " does not exist (looked in " + src + ")");
                } else if (!hasIndexableFile(src)) {
                    c.warn(kb.getLine(), who, "source " + kb.getSource() + " has no text files to index ("
                            + String.join(", ", new java.util.TreeSet<>(io.github.llm4j.loom.knowledge.KnowledgeIndexer.TEXT)) + ")");
                }
            }
            String problem = embeddings().problem(kb.getEmbeddingProvider());
            if (problem != null) c.error(kb.getLine(), who, problem);
            if (kb.getChunkSize() < 1) c.error(kb.getLine(), who, "chunk_size must be positive");
            else if (kb.getOverlap() >= kb.getChunkSize()) c.error(kb.getLine(), who, "overlap must be smaller than chunk_size");
        }
    }

    private static boolean hasIndexableFile(Path src) {
        try (java.util.stream.Stream<Path> all = java.nio.file.Files.walk(src)) {
            return all.filter(java.nio.file.Files::isRegularFile).anyMatch(p -> {
                String n = p.getFileName().toString();
                int dot = n.lastIndexOf('.');
                return dot > 0 && io.github.llm4j.loom.knowledge.KnowledgeIndexer.TEXT.contains(n.substring(dot + 1).toLowerCase());
            });
        } catch (java.io.IOException e) {
            return false;
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

    private void checkToolsAndApprovals(ScriptValidator.Checker c) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (io.github.llm4j.loom.ast.ToolDef t : script.getTools()) {
            if (!seen.add(t.getName())) c.error(t.getLine(), "tool " + t.getName(), "declared twice");
            for (String problem : toolFactory.problems(t, envLookup, baseDir)) c.error(t.getLine(), "tool " + t.getName(), problem);
        }
        // Built-in names an agent uses (not declared, not registered by the host): their keys must be set too
        java.util.Set<String> declared = new java.util.HashSet<>();
        script.getTools().forEach(t -> declared.add(t.getName()));
        java.util.Set<String> checkedBuiltIns = new java.util.HashSet<>();
        for (AgentDef a : script.getAgents()) {
            for (String name : a.getTools()) {
                if (declared.contains(name) || toolRegistry.getTool(name) != null || !checkedBuiltIns.add(name)) continue;
                io.github.llm4j.loom.ast.ToolDef builtIn = io.github.llm4j.loom.tools.ToolFactory.builtIn(name);
                if (builtIn == null) continue;
                for (String problem : toolFactory.problems(builtIn, envLookup, baseDir)) c.error(a.getLine(), "tool " + name, problem);
            }
        }
        for (AgentDef a : script.getAgents()) {
            String who = "agent " + a.getName();
            for (String name : a.getApprove()) {
                if (!a.getTools().contains(name)) {
                    c.error(a.getLine(), who, "approve: " + name + " is not one of its tools " + a.getTools());
                }
            }
            for (String name : a.getTools()) {
                for (io.github.llm4j.loom.ast.ToolDef def : script.getTools()) {
                    if (!def.getName().equals(name)) continue;
                    boolean approved = a.isApproveAll() || a.getApprove().contains(name);
                    String problem = toolFactory.agentProblem(def, envLookup, a.getName(), approved);
                    if (problem != null) c.error(a.getLine(), who, problem);
                }
            }
            if ((a.isApproveAll() || !a.getApprove().isEmpty()) && !c.context().hasHumanInterface()) {
                c.error(a.getLine(), who, "approve needs someone to ask: set a HumanInterface (weave provides the console)");
            }
        }
    }

    /** How many times this run has been resumed after pausing for a limit. */
    public int getResumes() {
        return resumes;
    }

    /** Prices for cost budgets and cost reporting (see {@link io.github.llm4j.budget.PriceTable}). */
    public void setPriceTable(io.github.llm4j.budget.PriceTable prices) {
        this.priceTable = prices;
    }

    /** How tokens are estimated before a call and when a provider reports none. */
    public void setTokenEstimator(io.github.llm4j.budget.TokenEstimator estimator) {
        this.tokenEstimator = estimator;
    }

    /**
     * Replaces the script's run limits (null keeps the script's value), e.g. from {@code weave --max-tokens}.
     * Call before {@link #initialize()}.
     */
    public void setBudgetOverrides(Long tokens, Long calls, java.math.BigDecimal cost) {
        this.overrideTokens = tokens;
        this.overrideCalls = calls;
        this.overrideCost = cost;
    }

    /** The run budget, or null when the script declares no budgets. */
    public io.github.llm4j.budget.Budget getRunBudget() {
        return runBudget;
    }

    /** An agent's own budget (its {@code budget { }} block), or null. */
    public io.github.llm4j.budget.Budget getAgentBudget(String agentName) {
        return agentBudgets.get(agentName);
    }

    /** What the run has spent so far: totals, per agent and per step. */
    public SpendReport spend() {
        return new SpendReport(List.copyOf(spendLines));
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
                return toolFactory.create(def, envLookup, baseDir, effectContext());
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

    private synchronized ApprovalGate approvals() {
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

    /** Runs {@code work} on another thread with this thread's step id and block-local names. */
    private CompletableFuture<Void> forkWith(String stepId, Map<String, Object> names, Runnable work) {
        List<io.github.llm4j.budget.Budget> budgets = scopes.get();
        return CompletableFuture.runAsync(() -> {
            step.set(stepId);
            locals.set(names);
            scopes.set(budgets);
            try {
                work.run();
            } finally {
                step.remove();
                locals.remove();
                scopes.remove();
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
        setUpBudgets();

        // ── 1c. Knowledge bases ───────────────────────────────────────────────
        indexKnowledge();

        // ── 2. Build agents ───────────────────────────────────────────────────
        for (AgentDef agentDef : script.getAgents()) {
            String systemPrompt = resolveSystemPrompt(agentDef);

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
                            metered(clientFor(policy.getPrimaryModel()), agentDef, policy.getPrimaryModel()));
                    for (String fb : policy.getFallbackModels()) {
                        routingBuilder.addClient(fallback ? ProviderTier.FAST_CHEAP : ProviderTier.BALANCED,
                                metered(clientFor(fb), agentDef, fb));
                    }
                    llmClient = routingBuilder.build();
                }
            }
            
            if (llmClient == null) {
                llmClient = metered(clientFor(agentDef.getModel()), agentDef, agentDef.getModel());
            }
            if (agentDef.getGuard() != null) {
                String judge = agentDef.getGuard().getBiasModel();
                io.github.llm4j.fairness.BiasMonitor monitor = judge == null
                        ? new io.github.llm4j.fairness.RuleBasedBiasMonitor()
                        : new io.github.llm4j.fairness.LLMBiasMonitor(metered(clientFor(judge), agentDef, judge));
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
            if (agentDef.getVoice() != null) voices.put(agentDef.getName(), new AgentVoice(agentDef.getVoice(), envLookup, baseDir));

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
            if (tracing()) agentBuilder.addListener(traceAdapter(agentDef.getName()));

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
        // Hydrate initial variables
        if (initialContext != null) {
            initialContext.forEach(context::setVariable);
        }

        WorkflowDef targetWorkflow = script.getWorkflows().stream()
            .filter(w -> w.getName().equals(workflowName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowName));

        log.info("Starting workflow: " + workflowName);
        if (budgeting) {
            restoreSpend();
            context.setVariable("_budget", new BudgetView());
        }

        String parent = step.get();
        boolean topLevel = parent.isEmpty();
        if (topLevel) beginRun();
        step.set(topLevel ? workflowName : parent + ">" + workflowName);
        try {
            rootNext.set(true);
            runBlock(targetWorkflow.getStatements(), "s");
            log.info("Workflow completed: " + workflowName);
        } catch (HandoffSignal hs) {
            log.info("Workflow terminated via handoff: " + hs.getMessage());
        } catch (RunSuspended paused) {
            if (topLevel && paused.resumeAt() != null) recordSuspension(paused);
            throw paused;
        } finally {
            step.set(parent);
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
            log.info("NOTE: " + resolvePayload(note.getMessage()));
        } else if (stmt instanceof DelegateStmt del) {
            executeDelegate(del);
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
                io.github.llm4j.agent.AgentResult result = runUnderLimits(agent, contextBriefing);
                
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
                if (broadcast.getBudget() != null && budgeting) {
                    scopes.set(plus(outerScopes, newBudget("step " + stepId, broadcast.getBudget())));
                }
                try {
                    Map<String, Object> names = locals.get();
                    List<io.github.llm4j.budget.Budget> branchScopes = scopes.get();
                    List<CompletableFuture<String>> answers = new java.util.ArrayList<>();
                    for (String agentName : broadcast.getTargetAgents()) {
                        ReActAgent broadcastAgent = activeAgents.get(agentName);
                        if (broadcastAgent == null) throw new IllegalStateException("Agent not found: " + agentName);
                        answers.add(CompletableFuture.supplyAsync(() -> {
                            step.set(stepId);
                            locals.set(names);
                            scopes.set(branchScopes);
                            try {
                                io.github.llm4j.agent.AgentResult r = runUnderLimits(broadcastAgent, resolvedPayload);
                                if (r.budgetExhausted()) {
                                    budgetExhausted.set(true);
                                    if (r.getUsage().getLlmCalls() == 0) throw r.getBudgetExceeded();
                                }
                                return r.getFinalAnswer();
                            } finally {
                                step.remove();
                                locals.remove();
                                scopes.remove();
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
            io.github.llm4j.budget.Budget loopBudget = loop.getBudget() != null && budgeting
                    ? newBudget("loop " + step.get(), loop.getBudget()) : null;
            List<io.github.llm4j.budget.Budget> outerScopes = scopes.get();
            if (loopBudget != null) scopes.set(plus(outerScopes, loopBudget));
            try {
                while (!ConditionEvaluator.evaluate(loop.getCondition(), view())) {
                    if (loop.getMaxIterations() > 0 && rounds >= loop.getMaxIterations()) {
                        exhaustedBy = "rounds";
                        break;
                    }
                    // A budget that already refused can't pay for another round (its steps may have
                    // handled the refusal in on_failure): stop instead of spinning.
                    if (anyRefused(loopBudget)) {
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
                String result = humanInterface.promptHuman(stepId, resolvedMessage);
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

    /**
     * Resolves the effective system prompt for an agent, in priority order:
     * 1. {@code system_template} id → looked up from PromptRegistry
     * 2. {@code persona} name       → looked up from PersonaLibrary via reflection
     * 3. Inline {@code system}      → used verbatim
     */
    private String resolveSystemPrompt(AgentDef agentDef) {
        String base = basePrompt(agentDef);
        // Skills always apply, whatever the base prompt came from.
        return agentDef.getSkills().isEmpty() ? base : base + "\n\n" + resolveSkills(agentDef.getSkills());
    }

    private String basePrompt(AgentDef agentDef) {
        // Priority 1: system_template
        if (agentDef.getSystemTemplate() != null && promptRegistry != null) {
            return promptRegistry.get(agentDef.getSystemTemplate())
                .map(t -> t.getTemplate())
                .orElseGet(() -> {
                    log.warning("system_template '" + agentDef.getSystemTemplate() + "' not found in PromptRegistry — falling back.");
                    return fallbackSystemPrompt(agentDef);
                });
        }

        // Persona (declared in the script, else PersonaLibrary), followed by the agent's own system prompt
        if (agentDef.getPersona() != null) {
            AgentPersona persona = persona(agentDef.getPersona());
            if (persona == null) throw new IllegalStateException("persona " + agentDef.getPersona() + " is not defined"); // validated earlier
            String system = fallbackSystemPrompt(agentDef);
            return system.isBlank() ? persona.toSystemPromptAddition() : persona.toSystemPromptAddition() + "\n\n" + system;
        }
        return fallbackSystemPrompt(agentDef);
    }

    /** A persona declared in the script, else a {@code PersonaLibrary} one, else null. */
    private AgentPersona persona(String name) {
        for (io.github.llm4j.loom.ast.PersonaDef def : script.getPersonas()) {
            if (!def.getName().equals(name)) continue;
            AgentPersona.Builder b = AgentPersona.builder().name(def.getName()).role(def.getRole());
            if (def.getExpertise() != null) b.expertise(def.getExpertise());
            if (def.getTone() != null) b.tone(def.getTone());
            if (def.getDescription() != null) b.description(def.getDescription());
            def.getConstraints().forEach(b::addConstraint);
            return b.build();
        }
        return ScriptValidator.libraryPersona(name);
    }

    private String resolveSkills(List<String> skillUris) {
        StringBuilder sb = new StringBuilder("## Skills\n");
        for (String uri : skillUris) {
            try {
                AgentSkill skill = ScriptValidator.loadSkill(uri, baseDir);
                sb.append("\n").append(skill.toSystemPromptSection()).append("\n");
                log.info("Loaded skill: " + skill.getName());
            } catch (Exception e) {
                // validated at load time; a skill that vanished since is still worth failing on
                throw new IllegalStateException("Skill " + uri + " can't be loaded: " + e.getMessage(), e);
            }
        }
        return sb.toString();
    }

    private Duration parseDuration(String raw) {
        if (raw == null || raw.isEmpty()) return Duration.ZERO;
        String val = raw.toLowerCase().trim();
        if (val.endsWith("s")) return Duration.ofSeconds(Long.parseLong(val.substring(0, val.length()-1)));
        if (val.endsWith("m")) return Duration.ofMinutes(Long.parseLong(val.substring(0, val.length()-1)));
        if (val.endsWith("h")) return Duration.ofHours(Long.parseLong(val.substring(0, val.length()-1)));
        return Duration.ofSeconds(Long.parseLong(val));
    }

    private String fallbackSystemPrompt(AgentDef agentDef) {
        return agentDef.getSystemPrompt() != null ? agentDef.getSystemPrompt() : "";
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
        io.github.llm4j.budget.Budget eachBudget = each.getBudget() != null && budgeting
                ? newBudget("for each " + parent, each.getBudget()) : null;
        List<io.github.llm4j.budget.Budget> outerScopes = scopes.get();
        if (eachBudget != null) scopes.set(plus(outerScopes, eachBudget));
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
            if (!each.isParallel() && anyRefused(eachBudget)) break;
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

    private void executeDelegate(DelegateStmt del) {
        attempt.set(delegateAttempts.incrementAndGet());
        String agentName = resolveName(del.getTargetAgent());
        String variableName = resolveName(del.getVariableName());
        AgentDef agentDef = script.getAgents().stream()
                .filter(a -> a.getName().equals(agentName))
                .findFirst().orElseThrow(() -> new IllegalStateException("Agent not found: " + agentName));
        ReActAgent agent = activeAgents.get(agentName);
        if (agent == null) throw new IllegalStateException("Agent not found: " + agentName);

        // A resumed run: this step already happened — reuse its recorded result, don't call the model.
        String stepId = step.get();
        var recorded = journal.get(stepId).filter(e -> !"retry".equals(e.kind())); // "retry": an operator asked for a failed step to be tried again
        if (recorded.isPresent()) {
            RunJournal.Entry entry = recorded.get();
            if ("failed".equals(entry.kind())) {
                handleExhausted(del, agentName, String.valueOf(entry.value()), null);
            } else {
                context.setVariable(variableName, entry.value());
                journal.get(stepId + "#audio").ifPresent(audio -> context.setVariable(variableName + "_audio", audio.value()));
                onDelegateReplayed(del, agentDef, entry.value());
                trace(TraceEvent.DELEGATE_REPLAYED, agentName, "reused the recorded result", null);
            }
            return;
        }

        String payload = resolvePayload(del.getPayload());
        AgentVoice voice = voices.get(agentName);
        Path heard = voice == null ? null : voice.audioTask(payload);
        if (heard != null) {
            try {
                payload = voice.transcribe(heard);
                trace(TraceEvent.VOICE, agentName, "heard " + baseDir.toAbsolutePath().normalize().relativize(heard.toAbsolutePath().normalize())
                        + ": " + payload, null);
            } catch (RuntimeException e) {
                failStep(del, stepId, agentName, "voice: couldn't transcribe " + heard.getFileName() + ": " + e.getMessage(), e);
                return;
            }
        }
        final String resolvedPayload = payload;

        String contextBriefing = memoryEngine.assembleContext(agentDef, resolvedPayload, view());
        io.github.llm4j.loom.knowledge.Retriever retriever = retrievers.get(agentName);
        if (retriever != null) {
            String found = io.github.llm4j.loom.knowledge.Retriever.format(retriever.search(resolvedPayload, retriever.topK()));
            if (!found.isEmpty()) contextBriefing = found + "\n" + contextBriefing;
        }
        AgentMemory agentMemory = memories.get(agentName);
        final String session = agentMemory == null ? null : agentMemory.session(this::resolvePayload);
        if (agentMemory != null) {
            sessions.put(stepId, session);
            AgentMemory.Recall recall = agentMemory.recall(session, resolvedPayload);
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("agent", agentName);
            data.put("session", session);
            data.put("messages", String.valueOf(recall.messages().size()));
            data.put("facts", String.valueOf(recall.facts().size()));
            auditLogger.logConversationEvent(sessionId, null, "memory_recalled", data);
            trace(TraceEvent.MEMORY, agentName, "recalled " + recall.messages().size() + " messages and "
                    + recall.facts().size() + " facts for " + session, data);
            if (!recall.isEmpty()) contextBriefing = recall.format(session) + contextBriefing;
        }
        contextBriefing = beforeDelegateExecution(del, agentDef, resolvedPayload, contextBriefing);
        AgentGuard guard = guards.get(agentName);
        if (guard != null) {
            try {
                guard.checkTask(contextBriefing);
            } catch (StepFailure blocked) {
                sessions.remove(stepId);
                failStep(del, stepId, agentName, blocked.getMessage(), blocked);
                return;
            }
        }
        trace(TraceEvent.DELEGATE_START, agentName, resolvedPayload, Map.of("variable", variableName));
        
        // A step's own `expecting { ... }` schema wins over the agent's output_schema.
        io.github.llm4j.loom.ast.SchemaDef schema = del.getExpecting() != null ? del.getExpecting() : agentDef.getOutputSchema();
        if (schema != null) {
            contextBriefing += "\n\nCRITICAL: You MUST respond in valid JSON format only, following this schema: "
                            + stringifySchema(schema);
        }

        int attempts = 0;
        int maxAttempts = del.getRetryCount() + 1;
        int[] limitWaits = {0};
        int[] asks = {0};
        Exception lastError = null;
        io.github.llm4j.budget.BudgetExceeded refused = null;
        io.github.llm4j.budget.BudgetExceeded partial = null;
        List<io.github.llm4j.budget.Budget> outerScopes = scopes.get();
        if (del.getBudget() != null && budgeting) {
            scopes.set(plus(outerScopes, newBudget("step " + stepId, del.getBudget())));
        }
        try {
        while (attempts < maxAttempts) {
            try {
                if (attempts > 0 && del.getBackoffMillis() > 0) {
                    Thread.sleep(del.getBackoffMillis() << Math.min(attempts - 1, 10));
                }
                log.info("Delegating to " + agentName + " (Attempt " + (attempts + 1) + ")");
                ReActAgent runAgent = agentForDelegate(del, agentDef, agent);
                io.github.llm4j.agent.AgentResult result = del.getTimeoutMillis() > 0
                        ? runWithTimeout(runAgent, contextBriefing, del.getTimeoutMillis())
                        : runAgent.run(contextBriefing);
                if (result.budgetExhausted()) {
                    io.github.llm4j.budget.BudgetExceeded be = result.getBudgetExceeded();
                    if (refills(be)) throw io.github.llm4j.ratelimit.RateLimited.of(be); // pause until it refills
                    if (askForMore(be, asks)) continue; // a person allowed more: run the step again
                    budgetExhausted.set(true);
                    // Refused before its first call: nothing to keep, the step failed.
                    if (result.getUsage().getLlmCalls() == 0) throw result.getBudgetExceeded();
                    partial = result.getBudgetExceeded(); // ran out part-way: keep its best answer
                }

                String answer = result.getFinalAnswer();
                if (guard != null) {
                    answer = guard.checkAnswer(answer);
                    if (!answer.equals(result.getFinalAnswer())) result = result.toBuilder().finalAnswer(answer).build();
                }
                Object finalValue = answer;
                if (schema != null && partial == null) {
                    finalValue = parseJsonResult(answer);
                }
                String spoken = null;
                if (voice != null && voice.speaks() && partial == null) {
                    try {
                        spoken = voice.speak(answer, variableName.replaceAll("[^A-Za-z0-9_-]", "_") + "-" + shortHash(stepId));
                    } catch (Exception e) {
                        throw new StepFailure("voice: couldn't speak the answer: " + e.getMessage(), e);
                    }
                    trace(TraceEvent.VOICE, agentName, "spoke the answer to " + spoken, null);
                }
                final String audio = spoken;
                final String remembered = answer;
                DelegateSuccessHandler successHandler = (agentResult, value) -> {
                    context.setVariable(variableName, value);
                    journal.put(stepId, new RunJournal.Entry("delegate", value));
                    if (audio != null) {
                        context.setVariable(variableName + "_audio", audio);
                        journal.put(stepId + "#audio", new RunJournal.Entry("audio", audio));
                    }
                    if (agentMemory != null) {
                        agentMemory.remember(session, guard != null ? guard.forStorage(resolvedPayload) : resolvedPayload, remembered);
                    }
                    trace(TraceEvent.DELEGATE_END, agentName, remembered, usageData(agentResult));
                    memoryEngine.storeOutcome(agentDef, resolvedPayload, agentResult, context);
                    auditLogger.logAgentDecision(AuditEvent.builder()
                            .sessionId(sessionId)
                            .agentResult(agentResult)
                            .addMetadata("agent", agentName)
                            .addMetadata("statement", "delegate")
                            .timestamp(Instant.now())
                            .build());
                };
                afterDelegateExecution(del, agentDef, resolvedPayload, contextBriefing, result, finalValue, successHandler);
                break; // Success (possibly a partial answer)
            } catch (io.github.llm4j.budget.BudgetExceeded exceeded) {
                budgetExhausted.set(true);
                refused = exceeded; // never retried: a retry would only spend more
                break;
            } catch (io.github.llm4j.ratelimit.RateLimited limited) {
                try {
                    onLimit(limited, limitWaits); // waited it out: run the step again (not a retry)
                    continue;
                } catch (RateLimitFailure f) {
                    lastError = f; // not worth waiting for: the step failed, without retries
                    attempts = maxAttempts;
                    break;
                }
            } catch (StepFailure failure) {
                lastError = failure; // a guard or voice failure: retrying wouldn't change it
                attempts = maxAttempts;
                break;
            } catch (io.github.llm4j.agent.AgentInterrupt interrupt) {
                throw interrupt; // waiting for a human is not a failure — never retried
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while delegating to " + agentName, e);
            } catch (Exception e) {
                log.warning("Delegation failed: " + e.getMessage());
                onDelegateError(del, agentDef, resolvedPayload, contextBriefing, e, attempts + 1, maxAttempts);
                lastError = e;
                attempts++;
            }
        }
        } finally {
            scopes.set(outerScopes);
            sessions.remove(stepId);
        }

        if (refused != null) {
            // Refused by a budget: on_failure handles it (outside the exhausted step budget), else it propagates.
            if (del.getOnFailure().isEmpty()) throw refused;
            handleExhausted(del, agentName, refused.getMessage(), refused);
            return;
        }
        if (partial != null) {
            if (!del.getOnFailure().isEmpty()) handleExhausted(del, agentName, partial.getMessage(), partial);
            return;
        }
        if (attempts < maxAttempts) return; // succeeded

        // Exhausted retries
        log.severe("Exhausted retries for delegate to " + agentName);
        String message = lastError != null && lastError.getMessage() != null ? lastError.getMessage() : "Unknown error";
        // Recorded only when on_failure lets the run continue past it; a step that fails the run is
        // retried when the run is resumed.
        if (!del.getOnFailure().isEmpty()) journal.put(stepId, new RunJournal.Entry("failed", message));
        handleExhausted(del, agentName, message, lastError);
    }

    /** A step that failed before its agent ran: recorded (when on_failure lets the run go on), then handled. */
    private void failStep(DelegateStmt del, String stepId, String agentName, String message, Exception cause) {
        log.warning("Step " + stepId + " failed: " + message);
        if (!del.getOnFailure().isEmpty()) journal.put(stepId, new RunJournal.Entry("failed", message));
        handleExhausted(del, agentName, message, cause);
    }

    private static String shortHash(String text) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> usageData(io.github.llm4j.agent.AgentResult r) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (r == null || r.getUsage() == null) return data;
        data.put("calls", r.getUsage().getLlmCalls());
        data.put("tokens", r.getUsage().getTotalTokens());
        if (r.getUsage().getCost() != null) data.put("cost", r.getUsage().getCost().toPlainString());
        return data;
    }

    /** Runs the on_failure block with {@code _error} in scope, or fails the workflow. */
    private void handleExhausted(DelegateStmt del, String agentName, String message, Exception cause) {
        if (!del.getOnFailure().isEmpty()) {
            Map<String, Object> outer = locals.get();
            Map<String, Object> names = new HashMap<>(outer);
            names.put("_error", message);
            locals.set(names);
            try {
                runBlock(del.getOnFailure(), "f");
            } finally {
                locals.set(outer);
            }
        } else {
            throw new RuntimeException("Delegate to " + agentName + " failed after " + del.getRetryCount()
                    + " retries: " + message, cause);
        }
    }

    private io.github.llm4j.agent.AgentResult runWithTimeout(ReActAgent agent, String task, long timeoutMillis) throws Exception {
        String stepId = step.get();
        Map<String, Object> names = locals.get();
        List<io.github.llm4j.budget.Budget> budgets = scopes.get();
        CompletableFuture<io.github.llm4j.agent.AgentResult> future = CompletableFuture.supplyAsync(() -> {
            step.set(stepId);
            locals.set(names);
            scopes.set(budgets);
            try {
                return agent.run(task);
            } finally {
                step.remove();
                locals.remove();
                scopes.remove();
            }
        }, BRANCHES);
        try {
            return future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new java.util.concurrent.TimeoutException("Step timed out after " + timeoutMillis + " ms");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    // ── Budgets ─────────────────────────────────────────────────────────────────────────────

    /** Builds the run and agent budgets, and decides whether clients are metered at all. */
    private void setUpBudgets() {
        io.github.llm4j.loom.ast.BudgetDef runDef = script.getBudget();
        boolean overridden = overrideTokens != null || overrideCalls != null || overrideCost != null;
        budgeting = runDef != null || overridden
                || script.getAgents().stream().anyMatch(a -> a.getBudget() != null)
                || script.getWorkflows().stream().anyMatch(w -> hasStatementBudget(w.getStatements()));
        if (!budgeting) return;

        io.github.llm4j.loom.ast.BudgetDef merged = new io.github.llm4j.loom.ast.BudgetDef();
        if (runDef != null) {
            merged.setTokens(runDef.getTokens());
            merged.setCalls(runDef.getCalls());
            merged.setCost(runDef.getCost());
            merged.setWarnAt(runDef.getWarnAt());
            merged.setWindow(runDef.getWindow());
            merged.setWhenExhausted(runDef.getWhenExhausted());
        }
        if (overrideTokens != null) merged.setTokens(overrideTokens);
        if (overrideCalls != null) merged.setCalls(overrideCalls);
        if (overrideCost != null) merged.setCost(overrideCost);
        runBudget = newBudget("run", merged);

        for (AgentDef agentDef : script.getAgents()) {
            if (agentDef.getBudget() != null && agentDef.getBudget().hasLimits()) {
                agentBudgets.put(agentDef.getName(), newBudget("agent " + agentDef.getName(), agentDef.getBudget()));
            }
        }
        // A cost limit needs a price for every model it covers — say so now, not mid-run.
        for (AgentDef agentDef : script.getAgents()) {
            boolean costed = runBudget.limits().cost() != null
                    || (agentBudgets.containsKey(agentDef.getName()) && agentBudgets.get(agentDef.getName()).limits().cost() != null);
            if (!costed) continue;
            for (String model : modelsOf(agentDef)) {
                if (priceTable == null || priceTable.price(model).isEmpty()) {
                    throw new IllegalStateException("A cost budget covers agent " + agentDef.getName() + ", but model '"
                            + model + "' has no price. Supply a price table (weave --prices <file>, or setPriceTable).");
                }
            }
        }
    }

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

    private static boolean hasStatementBudget(List<Statement> statements) {
        for (Statement st : statements) {
            if (st instanceof DelegateStmt d && (d.getBudget() != null || hasStatementBudget(d.getOnFailure()))) return true;
            if (st instanceof BroadcastStmt b && b.getBudget() != null) return true;
            if (st instanceof LoopStmt l && (l.getBudget() != null || hasStatementBudget(l.getBody())
                    || hasStatementBudget(l.getOnExhausted()))) return true;
            if (st instanceof ForEachStmt f && (f.getBudget() != null || hasStatementBudget(f.getBody()))) return true;
            if (st instanceof AltStmt a && (hasStatementBudget(a.getIfBranch())
                    || (a.getElseBranch() != null && hasStatementBudget(a.getElseBranch())))) return true;
            if (st instanceof ParallelStmt p && hasStatementBudget(p.getBody())) return true;
            if (st instanceof GuardrailStmt g && (hasStatementBudget(g.getBody()) || hasStatementBudget(g.getOnViolation()))) return true;
        }
        return false;
    }

    /** Meters a client against run + agent + enclosing budgets, when the script uses budgets. */
    private io.github.llm4j.LLMClient metered(io.github.llm4j.LLMClient client, AgentDef agentDef, String model) {
        if (!budgeting || client == null) return client;
        String agentName = agentDef.getName();
        io.github.llm4j.budget.BudgetedLLMClient metered = io.github.llm4j.budget.BudgetedLLMClient.builder(client)
                .budgets(() -> budgetsFor(agentName))
                .estimator(tokenEstimator)
                .prices(priceTable)
                .model(model)
                .perCallCap(agentDef.getBudget() != null ? agentDef.getBudget().getPerCall() : null)
                .build();
        metered.addChargeListener((m, charge) -> {
            String stepId = step.get();
            spendLines.add(new SpendReport.Line(stepId, agentName, m, charge));
            journalUsage(stepId, agentName, m, charge);
        });
        return metered;
    }

    /**
     * Records what a step's calls cost, cumulatively per step and agent, so a resumed run knows what was
     * already paid for — including calls of a step that never finished (they were billed all the same).
     */
    private synchronized void journalUsage(String stepId, String agentName, String model, io.github.llm4j.budget.Charge c) {
        String key = stepId + USAGE + agentName;
        Map<String, Object> prior = journal.get(key)
                .map(e -> e.value() instanceof Map<?, ?> m ? castMap(m) : Map.<String, Object>of())
                .orElse(Map.of());
        Map<String, Object> usage = new java.util.LinkedHashMap<>();
        usage.put("agent", agentName);
        usage.put("model", model);
        usage.put("prompt", number(prior.get("prompt")) + c.promptTokens());
        usage.put("completion", number(prior.get("completion")) + c.completionTokens());
        usage.put("calls", number(prior.get("calls")) + c.calls());
        usage.put("cost", money(prior.get("cost")).add(c.cost()).toPlainString());
        usage.put("estimated", Boolean.TRUE.equals(prior.get("estimated")) || c.estimated());
        usage.put("at", clock.millis()); // budget windows: a step's spend counts in the window of its last call
        journal.put(key, new RunJournal.Entry("usage", usage));
    }

    /** A resumed run: counts what earlier runs already spent (from the journal), once. */
    private synchronized void restoreSpend() {
        if (spendRestored) return;
        spendRestored = true;
        for (Map.Entry<String, RunJournal.Entry> e : journal.all().entrySet()) {
            if (!"usage".equals(e.getValue().kind()) || !(e.getValue().value() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> u = castMap(raw);
            io.github.llm4j.budget.Charge charge = new io.github.llm4j.budget.Charge(number(u.get("prompt")),
                    number(u.get("completion")), (int) number(u.get("calls")), money(u.get("cost")),
                    Boolean.TRUE.equals(u.get("estimated")));
            io.github.llm4j.budget.Spent spent = io.github.llm4j.budget.Spent.of(charge);
            Instant at = u.get("at") == null ? null : Instant.ofEpochMilli(number(u.get("at")));
            runBudget.restore(spent, at);
            String agent = String.valueOf(u.get("agent"));
            if (agentBudgets.containsKey(agent)) agentBudgets.get(agent).restore(spent, at);
            String stepId = e.getKey().substring(0, e.getKey().indexOf(USAGE));
            spendLines.add(new SpendReport.Line(stepId, agent, String.valueOf(u.get("model")), charge));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return 0;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static java.math.BigDecimal money(Object value) {
        if (value == null) return java.math.BigDecimal.ZERO;
        try {
            return new java.math.BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return java.math.BigDecimal.ZERO;
        }
    }

    private io.github.llm4j.budget.BudgetSet budgetsFor(String agentName) {
        return io.github.llm4j.budget.BudgetSet.of(runBudget, agentBudgets.get(agentName))
                .with(scopes.get().toArray(io.github.llm4j.budget.Budget[]::new));
    }

    private io.github.llm4j.budget.Budget newBudget(String name, io.github.llm4j.loom.ast.BudgetDef def) {
        io.github.llm4j.budget.Budget.Builder b = io.github.llm4j.budget.Budget.builder().name(name);
        if (def.getTokens() != null) b.tokens(def.getTokens());
        if (def.getCalls() != null) b.calls(def.getCalls());
        if (def.getCost() != null) b.cost(def.getCost());
        if (def.getWarnAt() != null) b.warnAt(def.getWarnAt());
        if (def.getWindow() != null) b.window(def.getWindow());
        b.clock(clock);
        io.github.llm4j.budget.Budget budget = b.build();
        budgetPolicies.put(name, def.getWhenExhausted() != null ? def.getWhenExhausted() : BudgetDef.WhenExhausted.STOP);
        budgetsByName.put(name, budget);
        originalLimits.put(name, budget.limits());
        budget.addListener(event -> {
            String kind = event.kind() == io.github.llm4j.budget.BudgetEvent.Kind.WARNING ? "budget_warning" : "budget_refused";
            log.warning(kind + ": " + event.budget() + " spent " + event.spent().tokens() + " tokens, "
                    + event.spent().calls() + " calls (limits: " + event.limits() + ")");
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("budget", event.budget());
            data.put("spentTokens", String.valueOf(event.spent().tokens()));
            data.put("spentCalls", String.valueOf(event.spent().calls()));
            data.put("spentCost", event.spent().cost().toPlainString());
            data.put("limits", event.limits().toString());
            auditLogger.logConversationEvent(sessionId, null, kind, data);
        });
        return budget;
    }

    private static List<io.github.llm4j.budget.Budget> plus(List<io.github.llm4j.budget.Budget> list,
                                                          io.github.llm4j.budget.Budget more) {
        List<io.github.llm4j.budget.Budget> all = new java.util.ArrayList<>(list);
        all.add(more);
        return List.copyOf(all);
    }

    /** True when {@code own}, the run budget, or any enclosing budget has already refused a call. */
    private boolean anyRefused(io.github.llm4j.budget.Budget own) {
        if (!budgeting) return false;
        if (own != null && own.refused()) return true;
        if (runBudget != null && runBudget.refused()) return true;
        for (io.github.llm4j.budget.Budget b : scopes.get()) if (b.refused()) return true;
        return false;
    }

    // ── Limits: pause, wait or fail ─────────────────────────────────────────────────────────

    private RateLimitDef.OnLimit onLimitPolicy() {
        RateLimitDef rl = script.getRateLimits();
        if (rl != null && rl.getOnLimit() != null) return rl.getOnLimit();
        return journal.isDurable() ? RateLimitDef.OnLimit.SUSPEND : RateLimitDef.OnLimit.WAIT;
    }

    private Duration maxWait(RateLimitDef.OnLimit policy) {
        RateLimitDef rl = script.getRateLimits();
        if (rl != null && rl.getMaxWait() != null) return rl.getMaxWait();
        return policy == RateLimitDef.OnLimit.WAIT ? DEFAULT_MAX_INLINE_WAIT : DEFAULT_MAX_SUSPEND;
    }

    private int maxResumes() {
        RateLimitDef rl = script.getRateLimits();
        return rl != null && rl.getMaxResumes() != null ? rl.getMaxResumes() : DEFAULT_MAX_RESUMES;
    }

    /**
     * A step hit a limit. Returns after waiting it out inline (the caller runs the step again), or throws
     * {@link RunSuspended} to pause the run until the reset, or {@link RateLimitFailure}.
     * A budget that refills pauses the run (its {@code when_exhausted: suspend}); a provider limit
     * follows {@code rate_limits { on_limit }}.
     */
    private void onLimit(io.github.llm4j.ratelimit.RateLimited limited, int[] waits) {
        io.github.llm4j.ratelimit.RateLimitInfo info = limited.info();
        boolean window = limited.reason() == io.github.llm4j.ratelimit.RateLimited.Reason.BUDGET_WINDOW;
        RateLimitDef.OnLimit policy = window ? RateLimitDef.OnLimit.SUSPEND : onLimitPolicy();
        Duration wait = info.waitFrom(clock.instant());
        Duration max = maxWait(policy);
        String stepId = step.get();
        if (policy == RateLimitDef.OnLimit.FAIL) throw new RateLimitFailure(info, null);
        if (wait.compareTo(max) > 0) {
            throw new RateLimitFailure(info, "that is more than max_wait " + human(max) + " away");
        }
        if (policy == RateLimitDef.OnLimit.SUSPEND) {
            throw new RunSuspended(stepId, window ? RunSuspended.Reason.BUDGET_WINDOW : RunSuspended.Reason.RATE_LIMIT, info);
        }
        if (++waits[0] > maxResumes()) {
            throw new RateLimitFailure(info, "still limited after " + (waits[0] - 1) + " waits (max_resumes)");
        }
        log.warning("Rate limited at " + stepId + " (" + info.describe() + "): waiting " + human(wait));
        Map<String, Object> data = limitData(info);
        data.put("step", stepId);
        data.put("waitMillis", String.valueOf(wait.toMillis()));
        auditLogger.logConversationEvent(sessionId, null, "rate_limit_wait", data);
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a rate limit", e);
        }
    }

    /** Runs an agent, waiting out or pausing for limits as {@link #onLimit} decides. */
    private io.github.llm4j.agent.AgentResult runUnderLimits(ReActAgent agent, String task) {
        int[] waits = {0};
        while (true) {
            try {
                io.github.llm4j.agent.AgentResult r = agent.run(task);
                if (r.budgetExhausted() && refills(r.getBudgetExceeded())) {
                    throw io.github.llm4j.ratelimit.RateLimited.of(r.getBudgetExceeded());
                }
                return r;
            } catch (io.github.llm4j.ratelimit.RateLimited limited) {
                onLimit(limited, waits);
            }
        }
    }

    /** True when the refusing budget refills at a known time and its script says to wait for that. */
    private boolean refills(io.github.llm4j.budget.BudgetExceeded be) {
        return be != null && be.resetAt().isPresent()
                && budgetPolicies.get(be.budget()) == BudgetDef.WhenExhausted.SUSPEND;
    }

    /**
     * {@code when_exhausted: ask}: asks a person whether to allow the budget's amount again. The answer is
     * journaled, so a resumed run gets the same top-up without asking twice. Returns true when allowed
     * (the budget has been raised); may pause the run ({@link RunSuspended}) until someone answers.
     */
    private boolean askForMore(io.github.llm4j.budget.BudgetExceeded be, int[] asks) {
        if (be == null || budgetPolicies.get(be.budget()) != BudgetDef.WhenExhausted.ASK) return false;
        io.github.llm4j.budget.Budget budget = budgetsByName.get(be.budget());
        io.github.llm4j.budget.Limits original = originalLimits.get(be.budget());
        if (budget == null || original == null) return false;
        String key = step.get() + "#more:" + be.budget() + ":" + (++asks[0]);
        String answer = journal.get(key).map(e -> String.valueOf(e.value())).orElse(null);
        if (answer == null) {
            if (humanInterface == null) return false; // nobody to ask: stop as usual
            String question = "Budget " + be.budget() + " is used up (" + be.getMessage() + "). Allow "
                    + describe(original) + " more? yes/no";
            answer = humanInterface.promptHuman(key, question);
            journal.put(key, new RunJournal.Entry("human", answer));
        }
        String a = answer.trim().toLowerCase(java.util.Locale.ROOT);
        if (!(a.startsWith("y") || a.equals("ok") || a.equals("approve") || a.equals("true"))) return false;
        budget.raise(original);
        log.info("Budget " + be.budget() + " raised by " + describe(original) + " (approved)");
        return true;
    }

    private static String describe(io.github.llm4j.budget.Limits l) {
        List<String> parts = new java.util.ArrayList<>();
        if (l.tokens() != null) parts.add(l.tokens() + " tokens");
        if (l.calls() != null) parts.add(l.calls() + " calls");
        if (l.cost() != null) parts.add("$" + l.cost().stripTrailingZeros().toPlainString());
        return String.join(", ", parts);
    }

    public static String human(Duration d) {
        long s = d.toSeconds();
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m" + (s % 60 == 0 ? "" : (s % 60) + "s");
        if (s < 86_400) return (s / 3600) + "h" + ((s % 3600) / 60 == 0 ? "" : ((s % 3600) / 60) + "m");
        return (s / 86_400) + "d" + ((s % 86_400) / 3600 == 0 ? "" : ((s % 86_400) / 3600) + "h");
    }

    private static Map<String, Object> limitData(io.github.llm4j.ratelimit.RateLimitInfo info) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("provider", info.provider());
        data.put("scope", info.scope().name());
        if (info.quotaId() != null) data.put("quotaId", info.quotaId());
        data.put("resumeAt", info.resetAt().toString());
        data.put("estimated", String.valueOf(info.estimated()));
        return data;
    }

    /** Start of a top-level run: counts a resume after a pause, and publishes {@code _run}. */
    private void beginRun() {
        Map<String, Object> last = journal.get(SUSPENSION)
                .map(e -> e.value() instanceof Map<?, ?> m ? new java.util.LinkedHashMap<>(castMap(m)) : null)
                .orElse(null);
        resumes = last == null ? 0 : (int) number(last.get("resumes"));
        if (last != null && "suspended".equals(last.get("state"))) {
            resumes++;
            Instant now = clock.instant();
            last.put("resumes", resumes);
            if (resumes > maxResumes()) {
                last.put("state", "failed");
                journal.put(SUSPENSION, new RunJournal.Entry("suspension", last));
                throw new IllegalStateException("Run paused for limits and resumed " + (resumes - 1)
                        + " times already (max_resumes " + maxResumes() + "); giving up. Last limit: " + last.get("detail"));
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
            rec.putAll(limitData(paused.limit()));
            rec.put("detail", paused.limit().describe());
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

    /** {@code _budget.*}: the run budget, read live. */
    private final class BudgetView extends java.util.AbstractMap<String, Object> {
        @Override
        public java.util.Set<Map.Entry<String, Object>> entrySet() {
            io.github.llm4j.budget.Spent spent = runBudget.spent();
            java.util.OptionalLong left = runBudget.remaining().tokens();
            Map<String, Object> view = new java.util.LinkedHashMap<>();
            view.put("spent", spent.tokens());
            view.put("remaining", left.isPresent() ? (Object) left.getAsLong() : "unlimited");
            view.put("calls", spent.calls());
            view.put("cost", spent.cost().stripTrailingZeros().toPlainString());
            view.put("exhausted", String.valueOf(budgetExhausted.get() || runBudget.exhausted()));
            return view.entrySet();
        }

        @Override
        public String toString() {
            return "budget" + entrySet();
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

    private String stringifySchema(SchemaDef schema) {
        if (schema == null) return "any";
        return switch (schema.getType()) {
            case OBJECT -> "{" + schema.getFields().entrySet().stream()
                    .map(e -> e.getKey() + ": " + stringifySchema(e.getValue()))
                    .collect(java.util.stream.Collectors.joining(", ")) + "}";
            case LIST -> "list<" + stringifySchema(schema.getElementType()) + ">";
            case ENUM -> "enum" + schema.getEnumValues().toString();
            default -> schema.getType().name().toLowerCase();
        };
    }

    private Object parseJsonResult(String raw) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String jsonPart = raw;
            if (raw.contains("```json")) {
                jsonPart = raw.substring(raw.indexOf("```json") + 7, raw.lastIndexOf("```"));
            } else if (raw.contains("```")) {
                jsonPart = raw.substring(raw.indexOf("```") + 3, raw.lastIndexOf("```"));
            }
            return mapper.readValue(jsonPart.trim(), Object.class);
        } catch (Exception e) {
            log.warning("Failed to parse JSON result: " + e.getMessage() + ". Returning raw string.");
            return raw;
        }
    }

    private static final java.util.regex.Pattern PAYLOAD_PATH =
            java.util.regex.Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)}");

    private String resolvePayload(String rawPayload) {
        if (rawPayload == null) return null;
        // {name} and {var.field.sub} are replaced by values; bare names (the legacy form) only in the text the
        // script wrote — never inside a value just inserted, so one variable's text can't rewrite another's.
        VariableContext scope = view();
        Map<String, Object> vars = scope.getAll();
        java.util.regex.Matcher m = PLACEHOLDER.matcher(rawPayload);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(bareNames(rawPayload.substring(last, m.start())));
            String name = m.group(1);
            if (PAYLOAD_PATH.matcher(m.group()).matches()) {
                // Like conditions, a missing field reads as empty (e.g. a step that failed and set nothing).
                Object value = io.github.llm4j.loom.runtime.ConditionEvaluator.resolvePath(name, scope);
                out.append(value != null ? String.valueOf(value) : "");
            } else if (vars.containsKey(name)) {
                out.append(String.valueOf(vars.get(name)));
            } else {
                out.append(m.group()); // not a variable: left as written
            }
            last = m.end();
        }
        out.append(bareNames(rawPayload.substring(last)));
        return out.toString();
    }

    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{([^{}\\s]+)}");

    /**
     * Bare-name substitution, for backward compatibility with older scripts. Workflow variables only:
     * block-local names (for each items, _error) are common words, so they must be written {like.this}.
     */
    private String bareNames(String text) {
        if (text.isEmpty()) return text;
        Map<String, Object> vars = context.getAll();
        if (vars.isEmpty()) return text;
        // One pass, longest names first: a value put in is never scanned again.
        String alternatives = vars.keySet().stream().filter(k -> !k.isEmpty())
                .sorted((x, y) -> Integer.compare(y.length(), x.length()))
                .map(java.util.regex.Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"));
        if (alternatives.isEmpty()) return text;
        return java.util.regex.Pattern.compile(alternatives).matcher(text)
                .replaceAll(r -> java.util.regex.Matcher.quoteReplacement(String.valueOf(vars.get(r.group()))));
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
