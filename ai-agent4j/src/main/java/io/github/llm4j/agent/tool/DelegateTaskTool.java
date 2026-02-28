package io.github.llm4j.agent.tool;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.Tool;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A highly capable tool that allows a Manager Agent to dynamically spawn and delegate tasks
 * to a specialized Sub-Agent. The sub-agent is constructed on-the-fly with a specific role,
 * instructions, and a limited set of tools retrieved from the ToolRegistry.
 */
public class DelegateTaskTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(DelegateTaskTool.class);

    private final ToolRegistry toolRegistry;
    private final LLMClient subAgentClient;

    /**
     * Constructs the DelegateTaskTool.
     *
     * @param toolRegistry   The registry used to lookup tools requested by the manager for the sub-agent.
     * @param subAgentClient The LLMClient to power the sub-agents (e.g. a RoutingLLMClient configured for fast/cheap tier).
     */
    public DelegateTaskTool(ToolRegistry toolRegistry, LLMClient subAgentClient) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry cannot be null");
        this.subAgentClient = Objects.requireNonNull(subAgentClient, "subAgentClient cannot be null");
    }

    @Override
    public String getName() {
        return "delegate_task";
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

        logger.info("Delegating task to sub-agent with role: {}", role);
        
        // 1. Resolve requested tools
        List<Tool> subAgentTools = toolRegistry.resolveTools(requiredTools);

        // 2. Build the Sub-Agent dynamically
        ReActAgent subAgent = ReActAgent.builder()
                .llmClient(subAgentClient)
                .systemPrompt("You are a " + role + ". Your sole objective is to follow these instructions and provide a final answer: " + instructions)
                .addTools(subAgentTools)
                // Use a transient memory store so the sub-agent starts fresh and leaves no trace
                .build();

        // 3. Execute the sub-agent's reasoning loop
        try {
            AgentResult result = subAgent.run(instructions);
            return "Task completed by sub-agent (" + role + "). Result:\n" + result.getFinalAnswer();
        } catch (Exception e) {
            logger.error("Sub-agent (" + role + ") failed to complete the task.", e);
            return "Sub-agent failed with error: " + e.getMessage();
        }
    }
}
