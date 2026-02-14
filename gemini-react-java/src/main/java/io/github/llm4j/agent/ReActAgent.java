package io.github.llm4j.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.memory.ConversationHistory;
import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.agent.prompt.PromptTemplate;
import io.github.llm4j.audit.AuditEvent;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.audit.NoOpAuditLogger;
import io.github.llm4j.fairness.BiasContext;
import io.github.llm4j.fairness.BiasEvent;
import io.github.llm4j.fairness.BiasMonitor;
import io.github.llm4j.fairness.NoOpBiasMonitor;
import io.github.llm4j.model.ConfidenceScore;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReActAgent {

    private static final Logger logger = LoggerFactory.getLogger(ReActAgent.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DEFAULT_SYSTEM_PROMPT = """
            Answer the following questions as best you can. You have access to the following tools:

            {tool_descriptions}

            Use the following format as a JSON object inside a ```json code block:

            {
              "thought": "you should always think about what to do",
              "action": "the action to take, should be one of [{tool_names}]",
              "action_input": {
                "parameter_name": "parameter_value"
              }
            }

            When you have the final answer, use this format:
            {
              "thought": "I now know the final answer",
              "final_answer": "the final answer to the original input question"
            }

            IMPORTANT: You must ONLY provide a single valid JSON object inside a ```json code block. Do NOT generate the Observation yourself.
            """;
            
    // Legacy patterns for backward compatibility
    private static final Pattern THOUGHT_PATTERN = Pattern.compile("Thought:\\s*(.+?)(?=\\n|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_PATTERN = Pattern.compile("Action:\\s*(.+?)(?=\\n|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT_PATTERN = Pattern.compile("Action Input:\\s*(.+?)(?=\\n|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FINAL_ANSWER_PATTERN = Pattern.compile("Final Answer:\\s*(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JSON_PATTERN = Pattern.compile("```json\\n(.*?)\\n```", Pattern.DOTALL);
    
    // ... (fields remain the same)
    private final LLMClient llmClient;
    private final Map<String, Tool> tools;
    private final String systemPrompt;
    private final int maxIterations;
    private final double temperature;
    private final AgentPersona persona;
    private final PromptRegistry promptRegistry;
    private final String systemPromptId;
    private final ConversationHistory conversationHistory;
    private final List<AgentEventListener> listeners;
    private final AuditLogger auditLogger;
    private final String sessionId;
    private final BiasMonitor biasMonitor;


    private ReActAgent(Builder builder) {
        // ... (constructor remains the same)
        this.llmClient = Objects.requireNonNull(builder.llmClient, "llmClient cannot be null");
        this.tools = new HashMap<>(builder.tools);
        this.persona = builder.persona;
        this.promptRegistry = builder.promptRegistry;
        this.systemPromptId = builder.systemPromptId;
        this.systemPrompt = resolveSystemPrompt(builder);
        this.maxIterations = builder.maxIterations;
        this.temperature = builder.temperature;
        this.conversationHistory = builder.conversationHistory;
        this.listeners = new ArrayList<>(builder.listeners);
        this.auditLogger = builder.auditLogger != null ? builder.auditLogger : new NoOpAuditLogger();
        this.sessionId = builder.sessionId != null ? builder.sessionId : UUID.randomUUID().toString();
        this.biasMonitor = builder.biasMonitor != null ? builder.biasMonitor : new NoOpBiasMonitor();
    }
    
    public AgentResult run(String question) {
        // ... (run method setup is the same)
        Objects.requireNonNull(question, "question cannot be null");

        List<AgentResult.AgentStep> steps = new ArrayList<>();
        StringBuilder scratchpad = new StringBuilder();
        scratchpad.append("Question: ").append(question).append("\n");

        Set<String> actionHistory = new HashSet<>();

        for (int i = 0; i < maxIterations; i++) {
            logger.debug("Agent iteration {}/{}", i + 1, maxIterations);
            
            // ... (request building is the same)
            String context = "";
            if (conversationHistory != null) {
                context = "Previous conversation history:\n" + conversationHistory.getFormattedHistory() + "\n";
            }
            LLMRequest request = LLMRequest.builder()
                    .addSystemMessage(systemPrompt)
                    .addUserMessage(context + scratchpad.toString())
                    .temperature(temperature)
                    .build();
            LLMResponse response = llmClient.chat(request);
            String llmOutput = response.getContent();
            logger.info("=== LLM Response (Iteration {}) ===\n{}", i + 1, llmOutput);

            try {
                Map<String, Object> responseJson = parseResponse(llmOutput);

                if (responseJson.containsKey("final_answer")) {
                    String finalAnswer = (String) responseJson.get("final_answer");
                    String thought = (String) responseJson.get("thought");
                    return processFinalAnswer(question, finalAnswer, thought, steps, i);
                }

                String thought = (String) responseJson.get("thought");
                String action = (String) responseJson.get("action");
                Object actionInputObj = responseJson.get("action_input");

                String actionInput = (actionInputObj instanceof String) 
                    ? (String) actionInputObj 
                    : objectMapper.writeValueAsString(actionInputObj);

                logger.info("Parsed - Thought: {}, Action: {}, ActionInput: {}", thought, action, actionInput);
                if (thought != null) notifyThought(thought);
                if (action != null) notifyAction(action, actionInput);
                
                if (action == null || action.isEmpty()) {
                    scratchpad.append(llmOutput).append("\nObservation: No valid action found.\n");
                    continue;
                }

                String observation = executeAction(action, actionInput, actionHistory);
                
                AgentResult.AgentStep step = new AgentResult.AgentStep(thought, action, actionInput, observation);
                steps.add(step);
                
                scratchpad.append("Thought: ").append(thought != null ? thought : "").append("\n");
                scratchpad.append("Action: ").append(action).append("\n");
                scratchpad.append("Action Input: ").append(actionInput != null ? actionInput : "").append("\n");
                scratchpad.append("Observation: ").append(observation).append("\n");

            } catch (Exception e) {
                logger.error("Failed to parse or process LLM response: {}", e.getMessage());
                scratchpad.append(llmOutput).append("\nObservation: Invalid response format.\n");
            }
        }
        return buildFailureResult(steps);
    }
    
    private Map<String, Object> parseResponse(String llmOutput) throws Exception {
        Matcher jsonMatcher = JSON_PATTERN.matcher(llmOutput);
        if (jsonMatcher.find()) {
            String jsonBlock = jsonMatcher.group(1);
            return objectMapper.readValue(jsonBlock, new TypeReference<>() {});
        }
        
        // Fallback for backward compatibility with old tests
        Map<String, Object> map = new HashMap<>();
        Matcher finalAnswerMatcher = FINAL_ANSWER_PATTERN.matcher(llmOutput);
        if (finalAnswerMatcher.find()) {
            map.put("thought", extractPattern(THOUGHT_PATTERN, llmOutput));
            map.put("final_answer", finalAnswerMatcher.group(1).trim());
            return map;
        }

        Matcher actionMatcher = ACTION_PATTERN.matcher(llmOutput);
        if (actionMatcher.find()) {
            map.put("thought", extractPattern(THOUGHT_PATTERN, llmOutput));
            map.put("action", actionMatcher.group(1).trim());
            map.put("action_input", extractPattern(ACTION_INPUT_PATTERN, llmOutput));
            return map;
        }
        
        throw new Exception("No valid JSON block or legacy format found in LLM output.");
    }

    private String executeAction(String action, String actionInput, Set<String> actionHistory) {
        String actionKey = action + ":" + (actionInput != null ? actionInput : "");
        if (actionHistory.contains(actionKey)) {
            logger.warn("Loop detected: {}", actionKey);
            return "Error: You have already taken this action with this input. Please try a different approach.";
        }
        actionHistory.add(actionKey);

        Tool tool = tools.get(action.toLowerCase());
        if (tool == null) {
            logger.warn("Unknown tool: {}", action);
            return "Error: Unknown tool '" + action + "'. Available tools: " + String.join(", ", tools.keySet());
        }

        try {
            logger.debug("Executing tool '{}' with input: {}", action, actionInput);
            Map<String, Object> args;
            if (actionInput != null && !actionInput.trim().isEmpty()) {
                try {
                    args = objectMapper.readValue(actionInput, new TypeReference<>() {});
                } catch (JsonProcessingException e) {
                    args = Map.of("input", actionInput); // Fallback for raw string
                }
            } else {
                args = new HashMap<>();
            }

            String observation = tool.execute(args);
            logger.info("Tool '{}' returned observation: {}", action, observation);
            notifyObservation(observation);
            return observation;
        } catch (Exception e) {
            logger.error("Error executing tool {}: {}", action, e.getMessage(), e);
            return "Error executing tool: " + e.getMessage();
        }
    }

    private AgentResult processFinalAnswer(String question, String finalAnswer, String thought, List<AgentResult.AgentStep> steps, int iteration) {
        if (thought != null) notifyThought(thought);
        if (conversationHistory != null) {
            conversationHistory.addUserMessage(question);
            conversationHistory.addAssistantMessage(finalAnswer);
        }

        AgentResult result = AgentResult.builder()
                .finalAnswer(finalAnswer)
                .steps(steps)
                .iterations(iteration + 1)
                .completed(true)
                .confidence(calculateConfidence(steps, iteration + 1, finalAnswer))
                .build();

        auditLogger.logAgentDecision(AuditEvent.builder().sessionId(sessionId).agentResult(result).build());

        BiasContext biasContext = BiasContext.builder().sessionId(sessionId).taskType("final_answer").build();
        List<BiasEvent> biasEvents = biasMonitor.detectBias(finalAnswer, biasContext);
        if (!biasEvents.isEmpty()) {
            logger.warn("Bias detected in final answer: {} events", biasEvents.size());
            biasEvents.forEach(event -> logger.warn("  - {}: {} (severity: {})", event.getType(), event.getExplanation(), event.getSeverity()));
        }
        return result;
    }

    private AgentResult buildFailureResult(List<AgentResult.AgentStep> steps) {
        logger.warn("Agent reached max iterations ({}) without finding final answer", maxIterations);
        String uncertaintyReason = "Agent reached maximum iterations without certainty";
        AgentResult result = AgentResult.builder()
                .finalAnswer("Maximum iterations reached without finding a final answer.")
                .steps(steps)
                .iterations(maxIterations)
                .completed(false)
                .confidence(ConfidenceScore.low(uncertaintyReason))
                .uncertaintyDetected(true)
                .uncertaintyReason(uncertaintyReason)
                .build();
        auditLogger.logAgentDecision(AuditEvent.builder().sessionId(sessionId).agentResult(result).build());
        return result;
    }

    // ... (rest of the class remains the same: getTools, extractPattern, resolveSystemPrompt, buildSystemPromptInjections, notify methods, calculateConfidence, toBuilder, builder)
    
    public Collection<Tool> getTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    private String extractPattern(Pattern pattern, String text) {
        if (text == null) return null;
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String resolveSystemPrompt(Builder builder) {
        // ... (same)
        if (builder.systemPrompt != null) {
            return builder.systemPrompt;
        }
        String baseTemplate = DEFAULT_SYSTEM_PROMPT;
        if (builder.promptRegistry != null && builder.systemPromptId != null) {
            Optional<PromptTemplate> template = builder.promptRegistry.get(builder.systemPromptId);
            if (template.isPresent()) {
                baseTemplate = template.get().getTemplate();
            } else {
                logger.warn("System prompt ID '{}' not found in registry. Using default.", builder.systemPromptId);
            }
        }
        return buildSystemPromptInjections(baseTemplate);
    }

    private String buildSystemPromptInjections(String baseTemplate) {
        // ... (same)
        StringBuilder prompt = new StringBuilder();
        if (persona != null) {
            prompt.append(persona.toSystemPromptAddition()).append("\n\n");
        }
        StringBuilder toolDescriptions = new StringBuilder();
        List<String> toolNames = new ArrayList<>();
        for (Tool tool : tools.values()) {
            toolDescriptions.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
            toolNames.add(tool.getName());
        }
        prompt.append(baseTemplate
                .replace("{tool_descriptions}", toolDescriptions.toString())
                .replace("{tool_names}", String.join(", ", toolNames)));
        return prompt.toString();
    }
    
    private void notifyThought(String thought) {
        for (AgentEventListener listener : listeners) {
            try {
                listener.onThought(thought);
            } catch (Exception e) {
                logger.error("Error in listener onThought", e);
            }
        }
    }

    private void notifyAction(String action, String input) {
        for (AgentEventListener listener : listeners) {
            try {
                listener.onAction(action, input);
            } catch (Exception e) {
                logger.error("Error in listener onAction", e);
            }
        }
    }

    private void notifyObservation(String observation) {
        for (AgentEventListener listener : listeners) {
            try {
                listener.onObservation(observation);
            } catch (Exception e) {
                logger.error("Error in listener onObservation", e);
            }
        }
    }
    
    private ConfidenceScore calculateConfidence(List<AgentResult.AgentStep> steps, int iterations, String finalAnswer) {
        // ... (same)
        double baseScore = 0.7;
        String reasoning = "Clean execution";
        double iterationRatio = (double) iterations / maxIterations;
        if (iterationRatio > 0.8) {
            baseScore -= 0.3;
            reasoning = "High iteration count (" + iterations + "/" + maxIterations + ")";
        } else if (iterationRatio > 0.5) {
            baseScore -= 0.1;
        }
        long failureCount = steps.stream().filter(step -> step.getObservation() != null && step.getObservation().startsWith("Error")).count();
        if (failureCount > 0) {
            baseScore -= (failureCount * 0.15);
            reasoning = failureCount + " tool failure(s)";
        }
        if (finalAnswer != null) {
            String lowerAnswer = finalAnswer.toLowerCase();
            if (lowerAnswer.contains("i don't know") || lowerAnswer.contains("i'm not sure") || lowerAnswer.contains("cannot determine") || lowerAnswer.contains("insufficient information")) {
                baseScore = 0.1;
                reasoning = "Agent expressed uncertainty";
            }
        }
        baseScore = Math.max(0.0, Math.min(1.0, baseScore));
        return ConfidenceScore.builder().score(baseScore).reasoning(reasoning).build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }
    
    public static final class Builder {
        // ... (same)
        private LLMClient llmClient;
        private Map<String, Tool> tools = new HashMap<>();
        private String systemPrompt;
        private int maxIterations = 10;
        private double temperature = 0.7;
        private AgentPersona persona;
        private PromptRegistry promptRegistry;
        private String systemPromptId;
        private ConversationHistory conversationHistory;
        private List<AgentEventListener> listeners = new ArrayList<>();
        private AuditLogger auditLogger;
        private String sessionId;
        private BiasMonitor biasMonitor;

        private Builder() {}
        private Builder(ReActAgent agent) {
            this.llmClient = agent.llmClient;
            this.tools = new HashMap<>(agent.tools);
            this.systemPrompt = agent.systemPrompt;
            this.maxIterations = agent.maxIterations;
            this.temperature = agent.temperature;
            this.persona = agent.persona;
            this.promptRegistry = agent.promptRegistry;
            this.systemPromptId = agent.systemPromptId;
            this.conversationHistory = agent.conversationHistory;
            this.listeners = new ArrayList<>(agent.listeners);
            this.auditLogger = agent.auditLogger;
            this.sessionId = agent.sessionId;
            this.biasMonitor = agent.biasMonitor;
        }
        
        public Builder llmClient(LLMClient llmClient) { this.llmClient = llmClient; return this; }
        public Builder addTool(Tool tool) { this.tools.put(tool.getName().toLowerCase(), tool); return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder maxIterations(int maxIterations) { this.maxIterations = maxIterations; return this; }
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }
        public Builder persona(AgentPersona persona) { this.persona = persona; return this; }
        public Builder promptRegistry(PromptRegistry promptRegistry) { this.promptRegistry = promptRegistry; return this; }
        public Builder systemPromptId(String systemPromptId) { this.systemPromptId = systemPromptId; return this; }
        public Builder conversationHistory(ConversationHistory history) { this.conversationHistory = history; return this; }
        public Builder addListener(AgentEventListener listener) { this.listeners.add(listener); return this; }
        public Builder auditLogger(AuditLogger auditLogger) { this.auditLogger = auditLogger; return this; }
        public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public Builder biasMonitor(BiasMonitor biasMonitor) { this.biasMonitor = biasMonitor; return this; }
        public ReActAgent build() { return new ReActAgent(this); }
    }
}
