package io.github.llm4j.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.memory.ConversationHistory;
import io.github.llm4j.agent.memory.SemanticMemoryConfig;
import io.github.llm4j.agent.memory.SemanticMemoryFactory;
import io.github.llm4j.agent.memory.SemanticMemoryService;
import io.github.llm4j.agent.persona.AgentPersona;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.agent.prompt.PromptTemplate;
import io.github.llm4j.agent.skill.AgentSkill;
import io.github.llm4j.audit.AuditEvent;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.audit.NoOpAuditLogger;
import io.github.llm4j.fairness.BiasContext;
import io.github.llm4j.fairness.BiasEvent;
import io.github.llm4j.fairness.BiasMonitor;
import io.github.llm4j.fairness.NoOpBiasMonitor;
import io.github.llm4j.media.AudioPlayer;
import io.github.llm4j.media.JavaAudioPlayer;
import io.github.llm4j.model.ConfidenceScore;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.TextToSpeechRequest;
import io.github.llm4j.model.TextToSpeechResponse;
import io.github.llm4j.model.TranscriptionRequest;
import io.github.llm4j.model.TranscriptionResponse;
import io.github.llm4j.provider.SpeechToTextProvider;
import io.github.llm4j.provider.TextToSpeechProvider;
import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReActAgent {

    private static final Logger logger = LoggerFactory.getLogger(ReActAgent.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DEFAULT_SYSTEM_PROMPT =
            """
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

            IMPORTANT: If you do not need to use any tool to answer the question, or if you are simply acknowledging a statement, you must provide your response directly in the "final_answer" field.
            You must ONLY provide a single valid JSON object inside a ```json code block. Do NOT generate the Observation yourself.
            """;

    // Legacy patterns for backward compatibility
    private static final Pattern THOUGHT_PATTERN =
            Pattern.compile("Thought:\\s*(.+?)(?=\\n|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_PATTERN =
            Pattern.compile("Action:\\s*(.+?)(?=\\n|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT_PATTERN =
            Pattern.compile("Action Input:\\s*(.+?)(?=\\n|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FINAL_ANSWER_PATTERN =
            Pattern.compile("Final Answer:\\s*(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JSON_PATTERN =
            Pattern.compile("```json\\n(.*?)\\n```", Pattern.DOTALL);

    // ... (fields remain the same)
    private final LLMClient llmClient;
    private final Map<String, Tool> tools;
    private final String systemPrompt;
    private final int maxIterations;
    private final double temperature;
    private final AgentPersona persona;
    private final List<AgentSkill> skills;
    private final PromptRegistry promptRegistry;
    private final String systemPromptId;
    private final ConversationHistory conversationHistory;
    private final SemanticMemoryService semanticMemoryService;
    private final List<AgentEventListener> listeners;
    private final AuditLogger auditLogger;
    private final String sessionId;
    private final BiasMonitor biasMonitor;
    private final TextToSpeechProvider ttsProvider;
    private final ApprovalCallback approvalCallback;

    private final SpeechToTextProvider sttProvider;
    private final AudioPlayer audioPlayer;
    private final boolean autoPlayAudio;
    private final String ttsLanguage;
    private final String ttsModel;

    private ReActAgent(Builder builder) {
        // ... (constructor remains the same)
        this.llmClient = Objects.requireNonNull(builder.llmClient, "llmClient cannot be null");
        this.tools = new HashMap<>(builder.tools);
        this.persona = builder.persona;
        this.skills = Collections.unmodifiableList(new ArrayList<>(builder.skills));
        this.promptRegistry = builder.promptRegistry;
        this.systemPromptId = builder.systemPromptId;
        this.systemPrompt = resolveSystemPrompt(builder);
        this.maxIterations = builder.maxIterations;
        this.temperature = builder.temperature;
        this.conversationHistory = builder.conversationHistory;
        this.semanticMemoryService = builder.semanticMemoryService;
        this.listeners = new ArrayList<>(builder.listeners);
        this.auditLogger =
                builder.auditLogger != null ? builder.auditLogger : new NoOpAuditLogger();
        this.sessionId =
                builder.sessionId != null ? builder.sessionId : UUID.randomUUID().toString();
        this.biasMonitor =
                builder.biasMonitor != null ? builder.biasMonitor : new NoOpBiasMonitor();
        this.ttsProvider = builder.ttsProvider;
        this.approvalCallback = builder.approvalCallback;

        this.sttProvider = builder.sttProvider;
        this.audioPlayer =
                builder.audioPlayer != null ? builder.audioPlayer : new JavaAudioPlayer();
        this.autoPlayAudio = builder.autoPlayAudio;
        this.ttsLanguage = builder.ttsLanguage;
        this.ttsModel = builder.ttsModel;
    }

    public AgentResult run(String question) {
        // ... (run method setup is the same)
        Objects.requireNonNull(question, "question cannot be null");

        // If question starts with "VOICE:" (or similar marker), we could infer?
        // But better to have explicit methods.

        List<AgentResult.AgentStep> steps = new ArrayList<>();
        StringBuilder scratchpad = new StringBuilder();
        scratchpad.append("Question: ").append(question).append("\n");

        Set<String> actionHistory = new HashSet<>();

        for (int i = 0; i < maxIterations; i++) {
            logger.debug("Agent iteration {}/{}", i + 1, maxIterations);

            // ... (request building is the same)
            String context = "";
            if (conversationHistory != null) {
                context += "Previous conversation history:\n"
                        + conversationHistory.getFormattedHistory()
                        + "\n\n";
            }
            if (semanticMemoryService != null && i == 0) {
                // Only recall on the very first iteration to save embedding tokens/time
                // Use a truncated version of the question to avoid massive embedding queries
                String memoryQuery = question.length() > 500 ? question.substring(0, 500) : question;
                List<String> facts = semanticMemoryService.recallRelevantFacts(memoryQuery, 5, 0.7f);
                if (!facts.isEmpty()) {
                    context += "Relevant context from user's long-term memory:\n";
                    for (String fact : facts) {
                        context += "- " + fact + "\n";
                    }
                    context += "\n";
                }
            }
            LLMRequest request =
                    LLMRequest.builder()
                            .addSystemMessage(systemPrompt)
                            .addUserMessage(context + scratchpad.toString())
                            .temperature(temperature)
                            .build();
            LLMResponse response = llmClient.chat(request);
            String llmOutput = response.getContent();
            logger.info("=== LLM Response (Iteration {}) ===\n{}", i + 1, llmOutput);

            try {
                Map<String, Object> responseJson;
                try {
                    responseJson = parseResponse(llmOutput);
                } catch (Exception e) {
                    // Fallback: If parsing fails, treat the entire output as the final answer
                    logger.warn(
                            "Failed to parse JSON, treating output as final answer: {}",
                            e.getMessage());
                    responseJson = new HashMap<>();
                    responseJson.put("final_answer", llmOutput);
                    responseJson.put(
                            "thought", "The model responded directly without JSON format.");
                }

                if (responseJson.containsKey("final_answer")) {
                    String finalAnswer = (String) responseJson.get("final_answer");
                    String thought = (String) responseJson.get("thought");
                    return processFinalAnswer(question, finalAnswer, thought, steps, i);
                }

                String thought = (String) responseJson.get("thought");
                String action = (String) responseJson.get("action");
                Object actionInputObj = responseJson.get("action_input");

                String actionInput =
                        (actionInputObj instanceof String)
                                ? (String) actionInputObj
                                : objectMapper.writeValueAsString(actionInputObj);

                logger.info(
                        "Parsed - Thought: {}, Action: {}, ActionInput: {}",
                        thought,
                        action,
                        actionInput);
                if (thought != null) notifyThought(thought);
                if (action != null) notifyAction(action, actionInput);

                if (action == null || action.isEmpty()) {
                    scratchpad.append(llmOutput).append("\nObservation: No valid action found.\n");
                    continue;
                }

                String observation = executeAction(action, actionInput, actionHistory, thought);

                AgentResult.AgentStep step =
                        new AgentResult.AgentStep(thought, action, actionInput, observation);
                steps.add(step);

                scratchpad.append("Thought: ").append(thought != null ? thought : "").append("\n");
                scratchpad.append("Action: ").append(action).append("\n");
                scratchpad
                        .append("Action Input: ")
                        .append(actionInput != null ? actionInput : "")
                        .append("\n");
                scratchpad.append("Observation: ").append(observation).append("\n");

            } catch (Exception e) {
                logger.error("Critical error in agent loop: {}", e.getMessage(), e);
                scratchpad
                        .append(llmOutput)
                        .append("\nObservation: System Error: ")
                        .append(e.getMessage())
                        .append("\n");
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

    private String executeAction(
            String action, String actionInput, Set<String> actionHistory, String thought) {
        String actionKey = action + ":" + (actionInput != null ? actionInput : "");
        if (actionHistory.contains(actionKey)) {
            logger.warn("Loop detected: {}", actionKey);
            return "Error: You have already taken this action with this input. Please try a different approach.";
        }
        actionHistory.add(actionKey);

        Tool tool = tools.get(action.toLowerCase());
        if (tool == null) {
            logger.warn("Unknown tool: {}", action);
            return "Error: Unknown tool '"
                    + action
                    + "'. Available tools: "
                    + String.join(", ", tools.keySet());
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

            // ── Human-in-the-Loop approval gate ──────────────────────────────
            if (tool.requiresApproval(args)) {
                notifyApprovalRequired(action, args, thought);
                if (approvalCallback == null) {
                    logger.warn("Tool '{}' requires approval but no ApprovalCallback is set. Blocking.", action);
                    return "Error: Action '" + action
                            + "' requires human approval, but no ApprovalCallback is configured."
                            + " Add one via ReActAgent.Builder#approvalCallback().";
                }
                boolean approved = approvalCallback.approve(action, args, thought != null ? thought : "");
                if (!approved) {
                    logger.info("Human rejected action '{}'. Feeding back to agent.", action);
                    return "Observation: A human supervisor rejected this action. "
                            + "Do not attempt it again. Choose a different approach to accomplish the goal.";
                }
                logger.info("Human approved action '{}'.", action);
            }
            // ─────────────────────────────────────────────────────────────────

            String observation = tool.execute(args);
            logger.info("Tool '{}' returned observation: {}", action, observation);
            notifyObservation(observation);
            return observation;
        } catch (Exception e) {
            logger.error("Error executing tool {}: {}", action, e.getMessage(), e);
            return "Error executing tool: " + e.getMessage();
        }
    }

    private AgentResult processFinalAnswer(
            String question,
            String finalAnswer,
            String thought,
            List<AgentResult.AgentStep> steps,
            int iteration) {
        if (thought != null) notifyThought(thought);
        if (conversationHistory != null) {
            conversationHistory.addUserMessage(question);
            conversationHistory.addAssistantMessage(finalAnswer);
        }

        AgentResult result =
                AgentResult.builder()
                        .finalAnswer(finalAnswer)
                        .steps(steps)
                        .iterations(iteration + 1)
                        .completed(true)
                        .confidence(calculateConfidence(steps, iteration + 1, finalAnswer))
                        .build();

        auditLogger.logAgentDecision(
                AuditEvent.builder().sessionId(sessionId).agentResult(result).build());

        BiasContext biasContext =
                BiasContext.builder().sessionId(sessionId).taskType("final_answer").build();
        List<BiasEvent> biasEvents = biasMonitor.detectBias(finalAnswer, biasContext);
        if (!biasEvents.isEmpty()) {
            logger.warn("Bias detected in final answer: {} events", biasEvents.size());
            biasEvents.forEach(
                    event ->
                            logger.warn(
                                    "  - {}: {} (severity: {})",
                                    event.getType(),
                                    event.getExplanation(),
                                    event.getSeverity()));
        }
        return result;
    }

    private AgentResult buildFailureResult(List<AgentResult.AgentStep> steps) {
        logger.warn(
                "Agent reached max iterations ({}) without finding final answer", maxIterations);
        String uncertaintyReason = "Agent reached maximum iterations without certainty";
        AgentResult result =
                AgentResult.builder()
                        .finalAnswer("Maximum iterations reached without finding a final answer.")
                        .steps(steps)
                        .iterations(maxIterations)
                        .completed(false)
                        .confidence(ConfidenceScore.low(uncertaintyReason))
                        .uncertaintyDetected(true)
                        .uncertaintyReason(uncertaintyReason)
                        .build();
        auditLogger.logAgentDecision(
                AuditEvent.builder().sessionId(sessionId).agentResult(result).build());
        return result;
    }

    // ... (rest of the class remains the same: getTools, extractPattern,
    // resolveSystemPrompt, buildSystemPromptInjections, notify methods,
    // calculateConfidence, toBuilder, builder)

    /**
     * Speaks the given text using the configured TTS provider.
     *
     * @param text The text to speak.
     * @return The audio data as byte array.
     */
    public byte[] speak(String text) {
        if (ttsProvider == null) {
            throw new IllegalStateException(
                    "TextToSpeechProvider is not configured for this agent.");
        }
        try {
            TextToSpeechRequest.Builder requestBuilder = TextToSpeechRequest.builder().text(text);

            // Resolve language and model
            if (ttsLanguage != null) {
                // Use LanguageMapper (assuming it's imported or fully qualified)
                String languageCode =
                        io.github.llm4j.util.LanguageMapper.getLanguageCode(ttsLanguage);
                requestBuilder.targetLanguageCode(languageCode);
            }

            // Default to bulbul:v2 if not specified, but only if we are using Sarvam
            // (implied by this logic being generic but we want smart defaults)
            String modelToUse = ttsModel != null ? ttsModel : "bulbul:v2";
            requestBuilder.model(modelToUse);

            TextToSpeechRequest request = requestBuilder.build();
            TextToSpeechResponse response = ttsProvider.generateSpeech(request);
            byte[] audioData = response.getAudioData();

            if (autoPlayAudio && audioPlayer != null) {
                audioPlayer.play(audioData, this.sessionId);
            }
            return audioData;
        } catch (Exception e) {
            logger.error("Failed to speak text", e);
            throw new RuntimeException("Failed to speak text", e);
        }
    }

    /**
     * Listens to the given audio file and transcribes it using the configured STT provider.
     *
     * @param audioFile The audio file to listen to.
     * @return The transcribed text.
     */
    public String listen(File audioFile) {
        if (sttProvider == null) {
            throw new IllegalStateException(
                    "SpeechToTextProvider is not configured for this agent.");
        }
        TranscriptionRequest request = TranscriptionRequest.builder().build(); // Default request
        TranscriptionResponse response = sttProvider.transcribe(audioFile, request);
        return response.getText();
    }

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
                logger.warn(
                        "System prompt ID '{}' not found in registry. Using default.",
                        builder.systemPromptId);
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
        if (!skills.isEmpty()) {
            prompt.append("## Skills\n\n");
            for (AgentSkill skill : skills) {
                prompt.append(skill.toSystemPromptSection()).append("\n\n");
            }
        }
        StringBuilder toolDescriptions = new StringBuilder();
        List<String> toolNames = new ArrayList<>();
        for (Tool tool : tools.values()) {
            toolDescriptions
                    .append("- ")
                    .append(tool.getName())
                    .append(": ")
                    .append(tool.getDescription())
                    .append("\n");
            toolNames.add(tool.getName());
        }
        prompt.append(
                baseTemplate
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

    private void notifyApprovalRequired(String toolName, Map<String, Object> args, String thought) {
        for (AgentEventListener listener : listeners) {
            try {
                listener.onApprovalRequired(toolName, args, thought);
            } catch (Exception e) {
                logger.error("Error in listener onApprovalRequired", e);
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

    private ConfidenceScore calculateConfidence(
            List<AgentResult.AgentStep> steps, int iterations, String finalAnswer) {
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
        long failureCount =
                steps.stream()
                        .filter(
                                step ->
                                        step.getObservation() != null
                                                && step.getObservation().startsWith("Error"))
                        .count();
        if (failureCount > 0) {
            baseScore -= (failureCount * 0.15);
            reasoning = failureCount + " tool failure(s)";
        }
        if (finalAnswer != null) {
            String lowerAnswer = finalAnswer.toLowerCase();
            if (lowerAnswer.contains("i don't know")
                    || lowerAnswer.contains("i'm not sure")
                    || lowerAnswer.contains("cannot determine")
                    || lowerAnswer.contains("insufficient information")) {
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
        private List<AgentSkill> skills = new ArrayList<>();
        private PromptRegistry promptRegistry;
        private String systemPromptId;
        private ConversationHistory conversationHistory;
        private SemanticMemoryService semanticMemoryService;
        private List<AgentEventListener> listeners = new ArrayList<>();
        private AuditLogger auditLogger;
        private String sessionId;
        private BiasMonitor biasMonitor;
        private TextToSpeechProvider ttsProvider;
        private ApprovalCallback approvalCallback;
        private SpeechToTextProvider sttProvider;
        private AudioPlayer audioPlayer;
        private boolean autoPlayAudio = true;
        private String ttsLanguage;
        private String ttsModel;

        private Builder() {}

        private Builder(ReActAgent agent) {
            this.llmClient = agent.llmClient;
            this.tools = new HashMap<>(agent.tools);
            this.systemPrompt = agent.systemPrompt;
            this.maxIterations = agent.maxIterations;
            this.temperature = agent.temperature;
            this.persona = agent.persona;
            this.skills = new ArrayList<>(agent.skills);
            this.promptRegistry = agent.promptRegistry;
            this.systemPromptId = agent.systemPromptId;
            this.conversationHistory = agent.conversationHistory;
            this.semanticMemoryService = agent.semanticMemoryService;
            this.listeners = new ArrayList<>(agent.listeners);
            this.auditLogger = agent.auditLogger;
            this.sessionId = agent.sessionId;
            this.biasMonitor = agent.biasMonitor;
            this.ttsProvider = agent.ttsProvider;
            this.approvalCallback = agent.approvalCallback;

            this.sttProvider = agent.sttProvider;
            this.audioPlayer = agent.audioPlayer;
            this.autoPlayAudio = agent.autoPlayAudio;
        }

        public Builder llmClient(LLMClient llmClient) {
            this.llmClient = llmClient;
            return this;
        }

        public Builder addTool(Tool tool) {
            this.tools.put(tool.getName().toLowerCase(), tool);
            return this;
        }

        public Builder addTools(Collection<Tool> tools) {
            for (Tool tool : tools) {
                addTool(tool);
            }
            return this;
        }

        public Builder clearTools() {
            this.tools.clear();
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

        public Builder persona(AgentPersona persona) {
            this.persona = persona;
            return this;
        }

        public Builder addSkill(AgentSkill skill) {
            this.skills.add(Objects.requireNonNull(skill, "skill cannot be null"));
            return this;
        }

        public Builder skills(List<AgentSkill> skills) {
            this.skills.addAll(Objects.requireNonNull(skills, "skills cannot be null"));
            return this;
        }

        public Builder clearSkills() {
            this.skills.clear();
            return this;
        }

        public Builder promptRegistry(PromptRegistry promptRegistry) {
            this.promptRegistry = promptRegistry;
            return this;
        }

        public Builder systemPromptId(String systemPromptId) {
            this.systemPromptId = systemPromptId;
            return this;
        }

        public Builder conversationHistory(ConversationHistory history) {
            this.conversationHistory = history;
            return this;
        }

        public Builder semanticMemory(SemanticMemoryService semanticMemoryService) {
            this.semanticMemoryService = semanticMemoryService;
            return this;
        }

        /**
         * One-liner convenience method that creates and wires up the full Semantic Memory
         * stack from a config object, and automatically registers the
         * {@link io.github.llm4j.agent.tool.MemoryManagementTool} so the agent can save facts.
         */
        public Builder semanticMemoryConfig(SemanticMemoryConfig config) {
            SemanticMemoryService service = SemanticMemoryFactory.create(config);
            this.semanticMemoryService = service;
            // Auto-register the MemoryManagementTool
            addTool(SemanticMemoryFactory.createTool(service));
            return this;
        }

        public Builder addListener(AgentEventListener listener) {
            this.listeners.add(listener);
            return this;
        }

        public Builder auditLogger(AuditLogger auditLogger) {
            this.auditLogger = auditLogger;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder biasMonitor(BiasMonitor biasMonitor) {
            this.biasMonitor = biasMonitor;
            return this;
        }

        public Builder ttsProvider(TextToSpeechProvider ttsProvider) {
            this.ttsProvider = ttsProvider;
            return this;
        }

        /**
         * Sets the Human-in-the-Loop approval callback. When set, any tool that returns
         * {@code true} from {@link Tool#requiresApproval(java.util.Map)} will be gated on this
         * callback before execution. If no callback is set and a tool requires approval, the agent
         * will refuse to execute it and inform the LLM to try an alternative.
         *
         * @param approvalCallback the callback to invoke for sensitive tool calls
         * @return this builder
         */
        public Builder approvalCallback(ApprovalCallback approvalCallback) {
            this.approvalCallback = approvalCallback;
            return this;
        }

        public Builder sttProvider(SpeechToTextProvider sttProvider) {
            this.sttProvider = sttProvider;
            return this;
        }

        public Builder audioPlayer(AudioPlayer audioPlayer) {
            this.audioPlayer = audioPlayer;
            return this;
        }

        public Builder autoPlayAudio(boolean autoPlayAudio) {
            this.autoPlayAudio = autoPlayAudio;
            return this;
        }

        public Builder ttsLanguage(String ttsLanguage) {
            this.ttsLanguage = ttsLanguage;
            return this;
        }

        public Builder ttsModel(String ttsModel) {
            this.ttsModel = ttsModel;
            return this;
        }

        public ReActAgent build() {
            return new ReActAgent(this);
        }
    }
}
