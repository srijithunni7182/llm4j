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

            ReActAgent.Builder agentBuilder = ReActAgent.builder()
                .llmClient(llmClient)
                .systemPrompt(systemPrompt);

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

        try {
            for (Statement stmt : targetWorkflow.getStatements()) {
                executeStatement(stmt);
            }
            log.info("Workflow completed: " + workflowName);
        } catch (HandoffSignal hs) {
            log.info("Workflow terminated via handoff: " + hs.getMessage());
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
            ReActAgent agent = activeAgents.get(del.getTargetAgent());
            if (agent == null) throw new IllegalStateException("Agent not found: " + del.getTargetAgent());

            String resolvedPayload = resolvePayload(del.getPayload());
            log.info("Delegating to " + del.getTargetAgent() + ": " + resolvedPayload);

            io.github.llm4j.agent.AgentResult result = agent.run(resolvedPayload);
            context.setVariable(del.getVariableName(), result.getFinalAnswer());

            auditLogger.logAgentDecision(AuditEvent.builder()
                .sessionId(sessionId)
                .agentResult(result)
                .addMetadata("agent", del.getTargetAgent())
                .addMetadata("statement", "delegate")
                .timestamp(Instant.now())
                .build());
        } else if (stmt instanceof HandoffStmt handoff) {
            ReActAgent agent = activeAgents.get(handoff.getTargetAgent());
            String resolvedPayload = resolvePayload(handoff.getPayload());
            log.info("Handoff to " + handoff.getTargetAgent() + " (Terminal node reached). Payload: " + resolvedPayload);

            if (agent != null) {
                io.github.llm4j.agent.AgentResult result = agent.run(resolvedPayload);
                auditLogger.logAgentDecision(AuditEvent.builder()
                    .sessionId(sessionId)
                    .agentResult(result)
                    .addMetadata("agent", handoff.getTargetAgent())
                    .addMetadata("statement", "handoff")
                    .timestamp(Instant.now())
                    .build());
            }
            // Signal end-of-branch – no further statements should execute.
            throw new HandoffSignal("Handoff to " + handoff.getTargetAgent() + " completed.");
        } else if (stmt instanceof AltStmt alt) {
            boolean condition = ConditionEvaluator.evaluate(alt.getCondition(), context);
            log.info("Evaluating AltStmt condition [" + alt.getCondition() + "] -> " + condition);
            List<Statement> branch = condition ? alt.getIfBranch() : alt.getElseBranch();
            if (branch != null) {
                for (Statement bStmt : branch) {
                    executeStatement(bStmt);
                }
            }
        } else if (stmt instanceof BroadcastStmt broadcast) {
            String resolvedPayload = resolvePayload(broadcast.getPayload());
            log.info("Broadcasting to " + broadcast.getTargetAgents() + ": " + resolvedPayload);

            List<String> results = broadcast.getTargetAgents().parallelStream().map(agentName -> {
                ReActAgent broadcastAgent = activeAgents.get(agentName);
                if (broadcastAgent == null) throw new IllegalStateException("Agent not found: " + agentName);
                return broadcastAgent.run(resolvedPayload).getFinalAnswer();
            }).toList();

            // Store as a JSON-like array string
            context.setVariable(broadcast.getVariableName(), results.toString());

            auditLogger.logConversationEvent(sessionId, null, "BROADCAST", Map.of(
                "agents",  broadcast.getTargetAgents().toString(),
                "payload", resolvedPayload
            ));
        } else if (stmt instanceof LoopStmt loop) {
            log.info("Entering loop. Condition: " + loop.getCondition());
            while (!ConditionEvaluator.evaluate(loop.getCondition(), context)) {
                for (Statement lStmt : loop.getBody()) {
                    executeStatement(lStmt);
                }
            }
            log.info("Exiting loop.");
        } else if (stmt instanceof HumanPromptStmt hp) {
            String resolvedMessage = resolvePayload(hp.getMessage());
            log.info("Human Prompt: " + resolvedMessage);
            if (humanInterface != null) {
                String result = humanInterface.promptHuman(resolvedMessage);
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
                for (String val : context.getAll().values()) {
                    if (piiDetector.detect(val).containsPII()) {
                        violation = true;
                        break;
                    }
                }
                
                if (violation) {
                    log.warning("PII violation detected! Executing on_violation branch.");
                    for (Statement vStmt : guard.getOnViolation()) {
                        executeStatement(vStmt);
                    }
                } else {
                    for (Statement bStmt : guard.getBody()) {
                        executeStatement(bStmt);
                    }
                }
            } else {
                // Default: just execute body
                for (Statement bStmt : guard.getBody()) {
                    executeStatement(bStmt);
                }
            }
        } else if (stmt instanceof ParallelStmt parallel) {
            log.info("Entering parallel block with " + parallel.getBody().size() + " branches.");
            List<CompletableFuture<Void>> futures = parallel.getBody().stream()
                .map(pStmt -> CompletableFuture.runAsync(() -> executeStatement(pStmt)))
                .toList();
            
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.info("Parallel block completed.");
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
    private String resolvePayload(String rawPayload) {
        String resolved = rawPayload;

        // Phase 1: delimited {varName} substitution — collision-safe, preferred syntax.
        for (Map.Entry<String, String> entry : context.getAll().entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
        }

        // Phase 2: bare-name substitution for backward compatibility with existing .loom scripts.
        // Sorted longest-key-first so that longer names (e.g. "child_id") are substituted
        // before their prefixes (e.g. "id"), minimising collision risk.
        java.util.List<Map.Entry<String, String>> entries = new java.util.ArrayList<>(context.getAll().entrySet());
        entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        for (Map.Entry<String, String> entry : entries) {
            resolved = resolved.replace(entry.getKey(), entry.getValue());
        }

        return resolved;
    }
}
