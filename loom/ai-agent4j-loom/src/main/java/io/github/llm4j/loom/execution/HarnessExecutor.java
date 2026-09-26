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
        return CompletableFuture.runAsync(() -> {
            step.set(stepId);
            locals.set(names);
            try {
                work.run();
            } finally {
                step.remove();
                locals.remove();
            }
        }, BRANCHES);
    }

    /** Waits for forked branches; a suspension or failure in any branch surfaces as itself. */
    private static void joinAll(List<CompletableFuture<Void>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
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
                    
                    routingBuilder.addClient(ProviderTier.REASONING, llmClientFactory.createClient(policy.getPrimaryModel()));
                    for (String fallback : policy.getFallbackModels()) {
                        routingBuilder.addClient(ProviderTier.BALANCED, llmClientFactory.createClient(fallback));
                    }
                    llmClient = routingBuilder.build();
                }
            }
            
            if (llmClient == null) {
                llmClient = llmClientFactory.createClient(agentDef.getModel());
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

        String parent = step.get();
        step.set(parent.isEmpty() ? workflowName : parent + ">" + workflowName);
        try {
            runBlock(targetWorkflow.getStatements(), "s");
            log.info("Workflow completed: " + workflowName);
        } catch (HandoffSignal hs) {
            log.info("Workflow terminated via handoff: " + hs.getMessage());
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
                io.github.llm4j.agent.AgentResult result = agent.run(contextBriefing);
                
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
                results = broadcast.getTargetAgents().parallelStream().map(agentName -> {
                    ReActAgent broadcastAgent = activeAgents.get(agentName);
                    if (broadcastAgent == null) throw new IllegalStateException("Agent not found: " + agentName);
                    return broadcastAgent.run(resolvedPayload).getFinalAnswer();
                }).toList().toString();
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
            boolean exhausted = false;
            while (!ConditionEvaluator.evaluate(loop.getCondition(), view())) {
                if (loop.getMaxIterations() > 0 && rounds >= loop.getMaxIterations()) {
                    exhausted = true;
                    break;
                }
                rounds++;
                context.setVariable("_loopRound", String.valueOf(rounds));
                runBlock(loop.getBody(), "r" + rounds + ".");
            }
            if (exhausted) {
                log.warning("Loop reached its max of " + loop.getMaxIterations() + " rounds without: " + loop.getCondition());
                context.setVariable("_loopRounds", String.valueOf(rounds));
                runBlock(loop.getOnExhausted(), "x");
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
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
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
                } finally {
                    step.set(parent);
                    locals.set(outer);
                }
            }
        }
        joinAll(futures);
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
        Exception lastError = null;

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
                
                Object finalValue = result.getFinalAnswer();
                if (schema != null) {
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
                return; // Success
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
        CompletableFuture<io.github.llm4j.agent.AgentResult> future = CompletableFuture.supplyAsync(() -> {
            step.set(stepId);
            locals.set(names);
            try {
                return agent.run(task);
            } finally {
                step.remove();
                locals.remove();
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
