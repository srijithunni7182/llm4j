package io.github.llm4j.agent;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of a ReAct (Reasoning and Acting) agent.
 * The agent uses a loop of Thought -> Action -> Observation to solve tasks.
 */
public class ReActAgent {

    private static final Logger logger = LoggerFactory.getLogger(ReActAgent.class);

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a helpful AI assistant that can use tools to answer questions.

            You have access to the following tools:
            {tool_descriptions}

            Use the following format:

            Question: the input question you must answer
            Thought: you should always think about what to do
            Action: the action to take, should be one of [{tool_names}]
            Action Input: the input to the action
            Observation: the result of the action
            ... (this Thought/Action/Action Input/Observation can repeat N times)
            Thought: I now know the final answer
            Final Answer: the final answer to the original input question

            Begin!
            """;

    private static final Pattern THOUGHT_PATTERN = Pattern.compile("Thought:\\s*(.+?)(?=\\n|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_PATTERN = Pattern.compile("Action:\\s*(.+?)(?=\\n|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT_PATTERN = Pattern.compile("Action Input:\\s*(.+?)(?=\\n|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FINAL_ANSWER_PATTERN = Pattern.compile("Final Answer:\\s*(.+?)(?=\\n|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final LLMClient llmClient;
    private final Map<String, Tool> tools;
    private final String systemPrompt;
    private final int maxIterations;
    private final double temperature;

    private ReActAgent(Builder builder) {
        this.llmClient = Objects.requireNonNull(builder.llmClient, "llmClient cannot be null");
        this.tools = new HashMap<>(builder.tools);
        this.systemPrompt = builder.systemPrompt != null ? builder.systemPrompt : buildDefaultSystemPrompt();
        this.maxIterations = builder.maxIterations;
        this.temperature = builder.temperature;
    }

    /**
     * Runs the agent to answer a question or complete a task.
     *
     * @param question the input question or task
     * @return the agent result containing the answer and execution steps
     */
    public AgentResult run(String question) {
        Objects.requireNonNull(question, "question cannot be null");

        List<AgentResult.AgentStep> steps = new ArrayList<>();
        StringBuilder scratchpad = new StringBuilder();
        scratchpad.append("Question: ").append(question).append("\n");

        for (int i = 0; i < maxIterations; i++) {
            logger.debug("Agent iteration {}/{}", i + 1, maxIterations);

            // Get LLM response
            LLMRequest request = LLMRequest.builder()
                    .addSystemMessage(systemPrompt)
                    .addUserMessage(scratchpad.toString())
                    .temperature(temperature)
                    .build();

            LLMResponse response = llmClient.chat(request);
            String llmOutput = response.getContent();

            logger.debug("LLM Output:\n{}", llmOutput);

            // Check for final answer
            Matcher finalAnswerMatcher = FINAL_ANSWER_PATTERN.matcher(llmOutput);
            if (finalAnswerMatcher.find()) {
                String finalAnswer = finalAnswerMatcher.group(1).trim();
                logger.info("Agent found final answer: {}", finalAnswer);

                return AgentResult.builder()
                        .finalAnswer(finalAnswer)
                        .steps(steps)
                        .iterations(i + 1)
                        .completed(true)
                        .build();
            }

            // Parse thought, action, and action input
            String thought = extractPattern(THOUGHT_PATTERN, llmOutput);
            String action = extractPattern(ACTION_PATTERN, llmOutput);
            String actionInput = extractPattern(ACTION_INPUT_PATTERN, llmOutput);

            if (action == null || action.isEmpty()) {
                logger.warn("No action found in iteration {}", i + 1);
                scratchpad.append(llmOutput).append("\n");
                scratchpad.append("Observation: No valid action found. Please use the format specified.\n");
                continue;
            }

            // Execute tool
            String observation;
            Tool tool = tools.get(action.toLowerCase());

            if (tool == null) {
                observation = "Error: Unknown tool '" + action + "'. Available tools: " +
                        String.join(", ", tools.keySet());
                logger.warn("Unknown tool: {}", action);
            } else {
                try {
                    logger.debug("Executing tool '{}' with input: {}", action, actionInput);
                    observation = tool.execute(actionInput != null ? actionInput : "");
                    logger.debug("Tool observation: {}", observation);
                } catch (Exception e) {
                    observation = "Error executing tool: " + e.getMessage();
                    logger.error("Error executing tool {}: {}", action, e.getMessage(), e);
                }
            }

            // Add step
            AgentResult.AgentStep step = new AgentResult.AgentStep(thought, action, actionInput, observation);
            steps.add(step);

            // Update scratchpad
            scratchpad.append("Thought: ").append(thought != null ? thought : "").append("\n");
            scratchpad.append("Action: ").append(action).append("\n");
            scratchpad.append("Action Input: ").append(actionInput != null ? actionInput : "").append("\n");
            scratchpad.append("Observation: ").append(observation).append("\n");
        }

        logger.warn("Agent reached max iterations ({}) without finding final answer", maxIterations);

        return AgentResult.builder()
                .finalAnswer("Maximum iterations reached without finding a final answer.")
                .steps(steps)
                .iterations(maxIterations)
                .completed(false)
                .build();
    }

    private String extractPattern(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String buildDefaultSystemPrompt() {
        StringBuilder toolDescriptions = new StringBuilder();
        List<String> toolNames = new ArrayList<>();

        for (Tool tool : tools.values()) {
            toolDescriptions.append("- ").append(tool.getName())
                    .append(": ").append(tool.getDescription()).append("\n");
            toolNames.add(tool.getName());
        }

        return DEFAULT_SYSTEM_PROMPT
                .replace("{tool_descriptions}", toolDescriptions.toString())
                .replace("{tool_names}", String.join(", ", toolNames));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LLMClient llmClient;
        private Map<String, Tool> tools = new HashMap<>();
        private String systemPrompt;
        private int maxIterations = 10;
        private double temperature = 0.7;

        private Builder() {
        }

        public Builder llmClient(LLMClient llmClient) {
            this.llmClient = llmClient;
            return this;
        }

        public Builder tools(List<Tool> tools) {
            for (Tool tool : tools) {
                this.tools.put(tool.getName().toLowerCase(), tool);
            }
            return this;
        }

        public Builder addTool(Tool tool) {
            this.tools.put(tool.getName().toLowerCase(), tool);
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public ReActAgent build() {
            return new ReActAgent(this);
        }
    }
}
