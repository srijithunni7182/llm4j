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
    /** The id of the step this thread is executing: its position in the script (stable across runs). */
    private final ThreadLocal<String> step = ThreadLocal.withInitial(() -> "");
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
    }

    public void setHumanInterface(HumanInterface humanInterface) { this.humanInterface = humanInterface; }
    public void setPromptRegistry(PromptRegistry promptRegistry) { this.promptRegistry = promptRegistry; }
    public void setAuditLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger != null ? auditLogger : new NoOpAuditLogger();
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
    public void addSuspensionListener(java.util.function.Consumer<RunSuspended> listener) {
        suspensionListeners.add(listener);
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
    private VariableContext view() {
        Map<String, Object> l = locals.get();
        return l.isEmpty() ? context : new ScopedContext(l, context);
    }

    /** Runs a block, giving each statement a stable step id under the current one. */
    private void runBlock(List<Statement> statements, String key) {
        String parent = step.get();
        try {
            for (int i = 0; i < statements.size(); i++) {
                step.set(parent + "/" + key + i);
                executeStatement(statements.get(i));
            }
        } finally {
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
                log.severe("Failed to initialise MCP server '" + mcpDef.getName() + "': " + e.getMessage());
            }
        }

        // ── 1b. Budgets ───────────────────────────────────────────────────────
        setUpBudgets();

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
                    RoutingLLMClient.Builder routingBuilder = RoutingLLMClient.builder()
                            .strategy(new CostAwareRoutingStrategy());
                    
                    routingBuilder.addClient(ProviderTier.REASONING,
                            metered(llmClientFactory.createClient(policy.getPrimaryModel()), agentDef, policy.getPrimaryModel()));
                    for (String fallback : policy.getFallbackModels()) {
                        routingBuilder.addClient(ProviderTier.BALANCED,
                                metered(llmClientFactory.createClient(fallback), agentDef, fallback));
                    }
                    llmClient = routingBuilder.build();
                }
            }
            
            if (llmClient == null) {
                llmClient = metered(llmClientFactory.createClient(agentDef.getModel()), agentDef, agentDef.getModel());
            }

            ReActAgent.Builder agentBuilder = ReActAgent.builder().llmClient(llmClient);
            if (agentDef.getTemperature() != null) {
                agentBuilder.temperature(agentDef.getTemperature());
            }
            if (agentDef.getTools().isEmpty() && agentDef.getMcpServers().isEmpty()) {
                agentBuilder.systemPrompt(systemPrompt);
            } else {
                // Tool-using agents keep the ReAct protocol (tool descriptions + JSON format);
                // a verbatim system prompt would hide their tools from the model.
                agentBuilder.instructions(systemPrompt);
            }

            // Reflection-based .loot tools
            for (String toolName : agentDef.getTools()) {
                Tool tool = toolRegistry.getTool(toolName);
                if (tool != null) {
                    agentBuilder.addTool(tool);
                } else {
                    log.warning("Tool not found in registry: " + toolName);
                }
            }

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
            
            // Tier 2: Wrap with RAG if knowledge bases are defined
            if (!agentDef.getKnowledgeBases().isEmpty()) {
                // In a real implementation, we'd look up properties from KnowledgeDef.
                // For now, we assume VectorStore and EmbeddingProvider are provided by the factory or environment.
                // RAGAgent ragAgent = RAGAgent.builder().agent(agent).vectorStore(...).embeddingProvider(...).build();
                // ragAgents.put(agentDef.getName(), ragAgent);
                log.info("RAG enabled for agent: " + agentDef.getName() + " (Placeholder implementation)");
            }
            
            // Tier 2: Setup memory
            if (agentDef.getMemory() != null) {
                log.info("Semantic memory enabled for agent: " + agentDef.getName() + " (Placeholder implementation)");
            }

            log.info("Initialized Agent: " + agentDef.getName());
        }

        // ── 3. Initialize Schedulers ──────────────────────────────────────────
        for (ScheduleDef sd : script.getSchedules()) {
            ReActAgent agent = activeAgents.get(sd.getAgentName());
            if (agent == null) {
                log.warning("Agent '" + sd.getAgentName() + "' not found for schedule '" + sd.getName() + "'");
                continue;
            }
            AgentScheduler scheduler = new AgentScheduler(agent);
            schedulers.put(sd.getName(), scheduler);

            Duration delay = parseDuration(sd.getInitialDelay());
            if (sd.getPattern() != null && !sd.getPattern().isEmpty()) {
                Duration period = parseDuration(sd.getPattern()); // Treat pattern as fixed-rate duration for now
                scheduler.scheduleRecurringTask(sd.getTask(), delay, period);
                log.info("Scheduled recurring task '" + sd.getName() + "' every " + sd.getPattern());
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
        if (stmt instanceof NoteStmt note) {
            log.info("NOTE: " + note.getMessage());
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
            var recorded = journal.get(stepId);
            if (recorded.isPresent()) {
                context.setVariable(hp.getVariableName(), String.valueOf(recorded.get().value()));
            } else if (humanInterface != null) {
                // May return now, or throw RunSuspended to wait without holding this thread.
                String result = humanInterface.promptHuman(stepId, resolvedMessage);
                journal.put(stepId, new RunJournal.Entry("human", result));
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
        // Priority 1: system_template
        if (agentDef.getSystemTemplate() != null && promptRegistry != null) {
            return promptRegistry.get(agentDef.getSystemTemplate())
                .map(t -> t.getTemplate())
                .orElseGet(() -> {
                    log.warning("system_template '" + agentDef.getSystemTemplate() + "' not found in PromptRegistry — falling back.");
                    return fallbackSystemPrompt(agentDef);
                });
        }

        // Priority 2: persona name → PersonaLibrary (reflective lookup)
        if (agentDef.getPersona() != null) {
            try {
                Method m = PersonaLibrary.class.getMethod(agentDef.getPersona());
                AgentPersona persona = (AgentPersona) m.invoke(null);
                log.info("Resolved persona '" + agentDef.getPersona() + "' for agent '" + agentDef.getName() + "'");
                return persona.toSystemPromptAddition();
            } catch (Exception e) {
                log.warning("Persona '" + agentDef.getPersona() + "' not found in PersonaLibrary — falling back. Error: " + e.getMessage());
            }
        }

        String finalPrompt = fallbackSystemPrompt(agentDef);
        
        // Tier 2: Append Skills
        if (!agentDef.getSkills().isEmpty()) {
            finalPrompt += "\n\n" + resolveSkills(agentDef.getSkills());
        }
        
        return finalPrompt;
    }

    private String resolveSkills(List<String> skillUris) {
        StringBuilder sb = new StringBuilder("## Skills\n");
        SkillLoader fsLoader = new FileSystemSkillLoader();
        
        for (String uri : skillUris) {
            try {
                AgentSkill skill;
                if (uri.startsWith("fs://")) {
                    skill = fsLoader.load(uri.substring(5));
                } else if (uri.startsWith("classpath://")) {
                    skill = AgentSkill.fromClasspath(uri.substring(12));
                } else {
                    skill = fsLoader.load(uri);
                }
                sb.append("\n").append(skill.toSystemPromptSection()).append("\n");
                log.info("Loaded skill: " + skill.getName());
            } catch (Exception e) {
                log.warning("Failed to load skill '" + uri + "': " + e.getMessage());
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
        String agentName = resolveName(del.getTargetAgent());
        String variableName = resolveName(del.getVariableName());
        AgentDef agentDef = script.getAgents().stream()
                .filter(a -> a.getName().equals(agentName))
                .findFirst().orElseThrow(() -> new IllegalStateException("Agent not found: " + agentName));
        ReActAgent agent = activeAgents.get(agentName);
        if (agent == null) throw new IllegalStateException("Agent not found: " + agentName);

        // A resumed run: this step already happened — reuse its recorded result, don't call the model.
        String stepId = step.get();
        var recorded = journal.get(stepId);
        if (recorded.isPresent()) {
            RunJournal.Entry entry = recorded.get();
            if ("failed".equals(entry.kind())) {
                handleExhausted(del, agentName, String.valueOf(entry.value()), null);
            } else {
                context.setVariable(variableName, entry.value());
                onDelegateReplayed(del, agentDef, entry.value());
            }
            return;
        }

        String resolvedPayload = resolvePayload(del.getPayload());
        
        String contextBriefing = memoryEngine.assembleContext(agentDef, resolvedPayload, view());
        contextBriefing = beforeDelegateExecution(del, agentDef, resolvedPayload, contextBriefing);
        
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

                Object finalValue = result.getFinalAnswer();
                if (schema != null && partial == null) {
                    finalValue = parseJsonResult(result.getFinalAnswer());
                }
                DelegateSuccessHandler successHandler = (agentResult, value) -> {
                    context.setVariable(variableName, value);
                    journal.put(stepId, new RunJournal.Entry("delegate", value));
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

    static String human(Duration d) {
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
        log.warning("Run paused at " + paused.stepId() + " (" + rec.get("detail") + "); resumes at " + paused.resumeAt());
        auditLogger.logConversationEvent(sessionId, null, "run_suspended", new java.util.LinkedHashMap<>(rec));
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
        String resolved = rawPayload;

        // Phase 0: {var.field.sub} paths into structured results (maps and lists), e.g. {report.verdict}
        // or {plan.hooks.0}.
        // Runs first so the bare-name phase below can't rewrite the variable name inside the braces.
        java.util.regex.Matcher paths = PAYLOAD_PATH.matcher(resolved);
        StringBuilder withPaths = new StringBuilder();
        while (paths.find()) {
            Object value = io.github.llm4j.loom.runtime.ConditionEvaluator.resolvePath(paths.group(1), view());
            // Like conditions, a missing field reads as empty (e.g. a step that failed and set nothing).
            String replacement = value != null ? String.valueOf(value) : "";
            paths.appendReplacement(withPaths, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        paths.appendTail(withPaths);
        resolved = withPaths.toString();

        // Phase 1: delimited {varName} substitution — collision-safe, preferred syntax.
        for (Map.Entry<String, Object> entry : view().getAll().entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }

        // Phase 2: bare-name substitution for backward compatibility with existing .loom scripts.
        // Workflow variables only: block-local names (for each items, _error) are common words, so they
        // must be written {like.this}.
        java.util.List<Map.Entry<String, Object>> entries = new java.util.ArrayList<>(context.getAll().entrySet());
        entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        for (Map.Entry<String, Object> entry : entries) {
            resolved = resolved.replace(entry.getKey(), String.valueOf(entry.getValue()));
        }

        return resolved;
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
