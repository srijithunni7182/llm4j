package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class HarnessExecutor {
    private static final Logger log = Logger.getLogger(HarnessExecutor.class.getName());

    private final LoomScript script;
    private final ToolRegistry toolRegistry;
    private final LLMClientFactory llmClientFactory;
    private final Map<String, ReActAgent> activeAgents = new HashMap<>();
    private final HarnessContext context;
    private HumanInterface humanInterface;

    public HarnessExecutor(LoomScript script, ToolRegistry toolRegistry, LLMClientFactory llmClientFactory) {
        this.script = script;
        this.toolRegistry = toolRegistry;
        this.llmClientFactory = llmClientFactory;
        this.context = new HarnessContext();
    }

    public void setHumanInterface(HumanInterface humanInterface) {
        this.humanInterface = humanInterface;
    }

    public void initialize() {
        for (AgentDef agentDef : script.getAgents()) {
            ReActAgent.Builder builder = ReActAgent.builder()
                .llmClient(llmClientFactory.createClient(agentDef.getModel()))
                .systemPrompt(agentDef.getSystemPrompt());

            for (String toolName : agentDef.getTools()) {
                Tool tool = toolRegistry.getTool(toolName);
                if (tool != null) {
                    builder.addTool(tool);
                } else {
                    log.warning("Tool not found in registry: " + toolName);
                }
            }

            activeAgents.put(agentDef.getName(), builder.build());
            log.info("Initialized Agent: " + agentDef.getName());
        }
    }

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

        for (Statement stmt : targetWorkflow.getStatements()) {
            executeStatement(stmt);
        }

        log.info("Workflow completed: " + workflowName);
    }

    public HarnessContext getContext() {
        return context;
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
        } else if (stmt instanceof HandoffStmt handoff) {
            ReActAgent agent = activeAgents.get(handoff.getTargetAgent());
            String resolvedPayload = resolvePayload(handoff.getPayload());
            log.info("Handoff to " + handoff.getTargetAgent() + " (External/Terminal node reached) Payload: " + resolvedPayload);
            
            if (agent != null) {
                agent.run(resolvedPayload);
            }
            // A handoff formally ends the branch but in a linear script it's just the final action
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
                ReActAgent agent = activeAgents.get(agentName);
                if (agent == null) throw new IllegalStateException("Agent not found: " + agentName);
                return agent.run(resolvedPayload).getFinalAnswer();
            }).toList();
            
            // Just store as a JSON-like array string
            context.setVariable(broadcast.getVariableName(), results.toString());
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
                log.warning("HumanInterface not configured. Storing empty string for human prompt.");
                context.setVariable(hp.getVariableName(), "");
            }
        }
    }

    // A simple interpolator for basic string concatenation e.g. "String: " + variable
    private String resolvePayload(String rawPayload) {
        // Poor man's interpolation for Phase 1. 
        // In the parser we stored literal or ID. If we want complex "A " + b, we need an Expression node.
        // For the sake of this PoC, we will check if it's a known variable, and if not, keep it as text.
        // Actually since we simplified the parser to just take single payload, let's substitute known vars.
        String resolved = rawPayload;
        for (Map.Entry<String, String> entry : context.getAll().entrySet()) {
            resolved = resolved.replace(entry.getKey(), entry.getValue());
        }
        return resolved;
    }
}
