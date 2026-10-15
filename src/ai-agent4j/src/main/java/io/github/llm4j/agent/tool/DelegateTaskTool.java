package io.github.llm4j.agent.tool;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentInterrupt;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ApprovalCallback;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.Tool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A tool that allows a Manager Agent to dynamically spawn and delegate tasks to a specialized Sub-Agent. The sub-agent is constructed on-the-fly
 * with a specific role, instructions, and a limited set of tools retrieved from the ToolRegistry.
 *
 * <p>Delegation is bounded. {@link Builder#maxDepth(int)} (default {@value #DEFAULT_MAX_DEPTH}) is how many generations may delegate: with 2, the
 * manager can have helpers, and those helpers can have helpers of their own, and no further. A helper at the limit is not given
 * {@code delegate_task} at all, and the manager cannot hand it a deeper copy by naming it in {@code requiredTools}.
 *
 * <p>Delegation never widens authority. {@link Builder#grantedTools(java.util.Collection)} names the tools the manager itself holds; a request
 * for any other tool is refused, and the sub-agent is not started, so the manager can ask again with a tool it has. Without it, every tool in
 * the registry can be handed out. A sub-agent has no approval callback unless one is given with {@link Builder#approvalCallback}: a tool
 * that needs approval then stays blocked for it, which is the safe default.
 */
public class DelegateTaskTool implements Tool {

    public static final String NAME = "delegate_task";
    public static final int DEFAULT_MAX_DEPTH = 2;

    private static final Logger logger = LoggerFactory.getLogger(DelegateTaskTool.class);

    private final ToolRegistry toolRegistry;
    private final LLMClient subAgentClient;
    private final int maxDepth;
    private final int level; // the generation of the helpers this tool creates: 1 for the manager's tool
    private final Set<String> granted; // null: anything in the registry
    private final ApprovalCallback approvalCallback;

    /**
     * Constructs the DelegateTaskTool with the default depth limit and no restriction on which registry tools can be handed out.
     *
     * @param toolRegistry   The registry used to lookup tools requested by the manager for the sub-agent.
     * @param subAgentClient The LLMClient to power the sub-agents (e.g. a RoutingLLMClient configured for fast/cheap tier).
     */
    public DelegateTaskTool(ToolRegistry toolRegistry, LLMClient subAgentClient) {
        this(toolRegistry, subAgentClient, DEFAULT_MAX_DEPTH, 1, null, null);
    }

    private DelegateTaskTool(ToolRegistry toolRegistry, LLMClient subAgentClient, int maxDepth, int level, Set<String> granted,
            ApprovalCallback approvalCallback) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry cannot be null");
        this.subAgentClient = Objects.requireNonNull(subAgentClient, "subAgentClient cannot be null");
        this.maxDepth = maxDepth;
        this.level = level;
        this.granted = granted;
        this.approvalCallback = approvalCallback;
    }

    public static Builder builder(ToolRegistry toolRegistry, LLMClient subAgentClient) {
        return new Builder(toolRegistry, subAgentClient);
    }

    /** Builds a {@link DelegateTaskTool}. */
    public static final class Builder {
        private final ToolRegistry toolRegistry;
        private final LLMClient subAgentClient;
        private int maxDepth = DEFAULT_MAX_DEPTH;
        private Set<String> granted;
        private ApprovalCallback approvalCallback;

        private Builder(ToolRegistry toolRegistry, LLMClient subAgentClient) {
            this.toolRegistry = toolRegistry;
            this.subAgentClient = subAgentClient;
        }

        /** How many generations may delegate (default 2). 0 turns delegation off; 1 lets the manager have helpers that cannot have their own. */
        public Builder maxDepth(int maxDepth) {
            if (maxDepth < 0) throw new IllegalArgumentException("maxDepth cannot be negative");
            this.maxDepth = maxDepth;
            return this;
        }

        /** The tools the manager holds, and so the only ones it can hand to a sub-agent. */
        public Builder grantedTools(Collection<String> names) {
            this.granted = names == null ? null : Set.copyOf(names);
            return this;
        }

        /** What a sub-agent asks when one of its tools needs approval (without it, such tools stay blocked for sub-agents). */
        public Builder approvalCallback(ApprovalCallback approvalCallback) {
            this.approvalCallback = approvalCallback;
            return this;
        }

        public DelegateTaskTool build() {
            return new DelegateTaskTool(toolRegistry, subAgentClient, maxDepth, 1, granted, approvalCallback);
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Use this tool to delegate complex tasks or tasks requiring specialized tools you do not possess. " +
               "You must provide a 'role' for the sub-agent, detailed 'instructions' of what to do, " +
               "and an array of 'requiredTools' (names) if it needs specific tools to complete the job. " +
               "Arguments: \n" +
               "- role (string): The persona of the sub-agent (e.g. 'Expert Python Developer').\n" +
               "- instructions (string): Crystal clear instructions on what the sub-agent must achieve.\n" +
               "- requiredTools (list of strings): Names of tools the sub-agent needs (e.g. ['calculator', 'web_search']).";
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> args) throws Exception {
        String role = (String) args.get("role");
        String instructions = (String) args.get("instructions");
        List<String> requiredTools = (List<String>) args.get("requiredTools");

        if (role == null || role.trim().isEmpty()) {
            return "Error: Missing or empty 'role' argument.";
        }
        if (instructions == null || instructions.trim().isEmpty()) {
            return "Error: Missing or empty 'instructions' argument.";
        }
        if (level > maxDepth) {
            return "Error: Delegation is not allowed here (maximum delegation depth " + maxDepth + " reached). Do the task yourself.";
        }

        // 1. Resolve requested tools, refusing any the manager does not itself hold
        List<String> refused = new ArrayList<>();
        List<Tool> subAgentTools = new ArrayList<>();
        boolean canDelegateFurther = level < maxDepth;
        if (requiredTools != null) {
            for (String name : requiredTools) {
                if (granted != null && !granted.contains(name)) {
                    refused.add(name);
                } else if (NAME.equals(name)) {
                    // never the same instance: the sub-agent gets a copy one generation deeper, or none at the limit
                    if (canDelegateFurther) {
                        subAgentTools.add(deeper());
                    } else {
                        refused.add(name + " (maximum delegation depth " + maxDepth + " reached)");
                    }
                } else {
                    subAgentTools.addAll(toolRegistry.resolveTools(List.of(name)));
                }
            }
        }
        if (!refused.isEmpty()) {
            logger.warn("Delegation refused: tools not available to the sub-agent: {}", refused);
            return "Error: Not available to a sub-agent: " + refused + ". "
                    + (granted == null ? "" : "You can hand out only " + new TreeSet<>(granted) + ". ")
                    + "Ask again with tools you have.";
        }

        logger.info("Delegating task to sub-agent with role: {} (generation {} of at most {})", role, level, maxDepth);

        // 2. Build the Sub-Agent dynamically
        ReActAgent.Builder builder = ReActAgent.builder()
                .llmClient(subAgentClient)
                .systemPrompt("You are a " + role + ". Your sole objective is to follow these instructions and provide a final answer: " + instructions)
                .addTools(subAgentTools);
        if (approvalCallback != null) builder.approvalCallback(approvalCallback);
        ReActAgent subAgent = builder.build();

        // 3. Execute the sub-agent's reasoning loop
        try {
            AgentResult result = subAgent.run(instructions);
            return "Task completed by sub-agent (" + role + "). Result:\n" + result.getFinalAnswer();
        } catch (AgentInterrupt interrupt) {
            throw interrupt; // a deliberate stop (waiting for a person) must reach the run that is waiting
        } catch (Exception e) {
            logger.error("Sub-agent (" + role + ") failed to complete the task.", e);
            return "Sub-agent failed with error: " + e.getMessage();
        }
    }

    /** The same tool for the next generation, which has the same limits. */
    private DelegateTaskTool deeper() {
        return new DelegateTaskTool(toolRegistry, subAgentClient, maxDepth, level + 1, granted, approvalCallback);
    }

    @Override
    public java.util.Map<String, Object> getParametersSchema() {
        return io.github.llm4j.model.ToolSchema.object().string("role", "The sub-agent's role", true).string("instructions", "The job for the sub-agent", true).stringArray("requiredTools", "Names of tools the sub-agent needs", false).build();
    }
}
