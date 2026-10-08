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
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetExceeded;
import io.github.llm4j.budget.BudgetPolicy;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.budget.TokenEstimator;
import io.github.llm4j.fairness.BiasContext;
import io.github.llm4j.fairness.BiasEvent;
import io.github.llm4j.fairness.BiasMonitor;
import io.github.llm4j.fairness.NoOpBiasMonitor;
import io.github.llm4j.media.AudioPlayer;
import io.github.llm4j.media.JavaAudioPlayer;
import io.github.llm4j.model.ConfidenceScore;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.model.TextToSpeechRequest;
import io.github.llm4j.model.TextToSpeechResponse;
import io.github.llm4j.model.TranscriptionRequest;
import io.github.llm4j.model.TranscriptionResponse;
import io.github.llm4j.provider.SpeechToTextProvider;
import io.github.llm4j.provider.TextToSpeechProvider;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
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
              "plan": "one short sentence on the next step",
              "action": "the action to take, should be one of [{tool_names}]",
              "action_input": {
                "parameter_name": "parameter_value"
              }
            }

            When you have the final answer, use this format:
            {
              "plan": "I have the answer",
              "final_answer": "the final answer to the original input question"
            }

            IMPORTANT: If you do not need to use any tool to answer the question, or if you are simply acknowledging a statement, you must provide your response directly in the "final_answer" field.
            You must ONLY provide a single valid JSON object inside a ```json code block. Do NOT generate the Observation yourself.
            """;

    // Legacy patterns for backward compatibility
    private static final Pattern THOUGHT_PATTERN =
            Pattern.compile("(?:Thought|Plan):\\s*(.+?)(?=\\r?\\n|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_PATTERN =
            Pattern.compile("Action:\\s*(.+?)(?=\\r?\\n|$)", Pattern.CASE_INSENSITIVE);
    /** The input runs to the next protocol line (or the end), so a multi-line JSON input survives. */
    private static final Pattern ACTION_INPUT_PATTERN =
            Pattern.compile(
                    "Action Input:\\s*(.+?)(?=\\r?\\n\\s*(?:Observation|Thought|Plan|Action|Final Answer)\\s*:|\\z)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FINAL_ANSWER_PATTERN =
            Pattern.compile("Final Answer:\\s*(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** A fenced JSON block: any case of {@code json}, spaces, LF or CRLF, with or without a newline before the closing fence. */
    private static final Pattern JSON_PATTERN =
            Pattern.compile("```[ \\t]*json\\b\\s*(.*?)\\s*```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** How the agent asks the model to use tools. */
    public enum ToolCalling {
        /** Native tool calling when the client supports it and the default prompt is used; otherwise the text protocol. The default. */
        AUTO,
        /** Always native; building the agent fails when the client or the tools cannot support it. */
        NATIVE,
        /** The text protocol: the model writes a JSON block that the agent parses. */
        TEXT
    }

    private static final String NATIVE_SYSTEM_PROMPT =
            """
            You are a helpful assistant with access to tools. Call a tool when it helps, using the exact tool name and its arguments.
            Use a tool's result as given and never invent one. When you have the answer, or no tool is needed, reply directly in plain text.
            """;

    // ... (fields remain the same)
    private final ToolCalling toolCalling;
    private final boolean nativeCalling;
    private final boolean customPrompt;
    private final String nativePrompt;
    private final LLMClient llmClient;
    private final LLMClient baseClient; // as given to the builder, before any budget wrapping
    private final Budget budget;
    private final Integer maxTokensPerCall;
    private final BudgetPolicy budgetPolicy;
    private final PriceTable priceTable;
    private final String budgetModel;
    private final TokenEstimator tokenEstimator;
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
    private final int recallTopK;
    private final float recallMinSimilarity;
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
        this.baseClient = Objects.requireNonNull(builder.llmClient, "llmClient cannot be null");
        this.budget = builder.budget;
        this.maxTokensPerCall = builder.maxTokensPerCall;
        this.budgetPolicy = builder.budgetPolicy;
        this.priceTable = builder.priceTable;
        this.budgetModel = builder.budgetModel;
        this.tokenEstimator = builder.tokenEstimator;
        this.llmClient = budgeted(baseClient);
        this.tools = new HashMap<>(builder.tools);
        this.persona = builder.persona;
        this.skills = Collections.unmodifiableList(new ArrayList<>(builder.skills));
        this.promptRegistry = builder.promptRegistry;
        this.systemPromptId = builder.systemPromptId;
        this.systemPrompt = resolveSystemPrompt(builder);
        this.customPrompt = builder.inheritedPrompt ? false : builder.systemPrompt != null || usesRegistryTemplate(builder);
        this.nativePrompt = builder.inheritedPrompt ? builder.inheritedNativePrompt : buildNativePrompt(builder);
        this.toolCalling = builder.toolCalling;
        this.nativeCalling = decideNative(builder);
        this.maxIterations = builder.maxIterations;
        this.temperature = builder.temperature;
        this.conversationHistory = builder.conversationHistory;
        this.semanticMemoryService = builder.semanticMemoryService;
        this.recallTopK = builder.recallTopK;
        this.recallMinSimilarity = builder.recallMinSimilarity;
        this.listeners = new ArrayList<>(builder.listeners);
        if (budget != null && !listeners.isEmpty()) {
            budget.addListener(event -> listeners.forEach(l -> l.onBudget(event)));
        }
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

    private static boolean usesRegistryTemplate(Builder builder) {
        return builder.promptRegistry != null
                && builder.systemPromptId != null
                && builder.promptRegistry.get(builder.systemPromptId).isPresent();
    }

    private String buildNativePrompt(Builder builder) {
        if (builder.systemPrompt != null) return builder.systemPrompt; // their prompt, as given
        StringBuilder prompt = new StringBuilder();
        if (builder.instructions != null && !builder.instructions.isBlank()) prompt.append(builder.instructions.trim()).append("\n\n");
        if (persona != null) prompt.append(persona.toSystemPromptAddition()).append("\n\n");
        if (!skills.isEmpty()) {
            prompt.append("## Skills\n\n");
            for (AgentSkill skill : skills) prompt.append(skill.toSystemPromptSection()).append("\n\n");
        }
        return prompt.append(NATIVE_SYSTEM_PROMPT).toString();
    }

    /** Native calling needs a client that supports it, at least one tool, and function-legal tool names. */
    private boolean decideNative(Builder builder) {
        if (builder.toolCalling == ToolCalling.TEXT) return false;
        boolean supported = llmClient.supportsToolCalling();
        boolean hasTools = !tools.isEmpty();
        boolean legal = tools.values().stream().allMatch(t -> ToolSpec.isLegalName(t.getName()));
        if (builder.toolCalling == ToolCalling.NATIVE) {
            if (!supported) throw new IllegalStateException("toolCalling(NATIVE) needs an LLMClient that supports tool calling (Gemini or Claude); this one does not");
            if (!hasTools) throw new IllegalStateException("toolCalling(NATIVE) needs at least one tool");
            if (!legal) throw new IllegalStateException("toolCalling(NATIVE) needs tool names of letters, digits, _ or - (at most 64): " + tools.values().stream().map(Tool::getName).filter(n -> !ToolSpec.isLegalName(n)).toList());
            return true;
        }
        if (supported && hasTools && legal && !customPrompt) return true;
        if (supported && hasTools && !legal) logger.info("Using the text protocol: some tool names are not legal function names");
        return false;
    }

    /** Wraps the client in a {@link BudgetedLLMClient} when this agent has a budget or a per-call cap. */
    private LLMClient budgeted(LLMClient client) {
        if ((budget == null && maxTokensPerCall == null) || client instanceof BudgetedLLMClient) return client;
        BudgetedLLMClient.Builder b = BudgetedLLMClient.builder(client)
                .perCallCap(maxTokensPerCall)
                .prices(priceTable)
                .model(budgetModel)
                .estimator(tokenEstimator);
        if (budget != null) b.budget(budget);
        return b.build();
    }

    public AgentResult run(String question) {
        // ... (run method setup is the same)
        Objects.requireNonNull(question, "question cannot be null");
        if (nativeCalling) return runNative(question);

        // If question starts with "VOICE:" (or similar marker), we could infer?
        // But better to have explicit methods.

        List<AgentResult.AgentStep> steps = new ArrayList<>();
        StringBuilder scratchpad = new StringBuilder();
        scratchpad.append("Question: ").append(question).append("\n");

        Set<String> actionHistory = new HashSet<>();
        AtomicInteger redundantActionCount = new AtomicInteger(0);
        int llmCallCount = 0;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        int totalTokens = 0;
        boolean usageEstimated = false;
        java.math.BigDecimal totalCost = null;
        String lastThought = null;
        String lastObservation = null;
        boolean protocolFollowed = true;

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
                List<String> facts = semanticMemoryService.recallRelevantFacts(memoryQuery, recallTopK, recallMinSimilarity);
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
            LLMResponse response;
            try {
                response = llmClient.chat(request);
            } catch (io.github.llm4j.exception.RateLimitException limited) {
                // Too long to wait inline (the HTTP layer already waited out short limits): hand the
                // reset time to the caller instead of retrying or answering half-way.
                throw new io.github.llm4j.ratelimit.RateLimited(
                        limited.infoOrEstimate(java.time.Instant.now(), java.time.Duration.ofSeconds(60)),
                        io.github.llm4j.ratelimit.RateLimited.Reason.PROVIDER_LIMIT);
            } catch (BudgetExceeded exhausted) {
                if (budgetPolicy == BudgetPolicy.FAIL) throw exhausted;
                if (budgetPolicy == BudgetPolicy.SUSPEND && exhausted.resetAt().isPresent()) {
                    throw io.github.llm4j.ratelimit.RateLimited.of(exhausted);
                }
                return buildBudgetExhaustedResult(
                        exhausted,
                        lastThought != null ? lastThought : lastObservation != null ? lastObservation : "",
                        steps,
                        i,
                        new AgentResult.Usage(llmCallCount, totalPromptTokens, totalCompletionTokens,
                                totalTokens, usageEstimated, totalCost),
                        redundantActionCount.get(),
                        protocolFollowed);
            }
            String llmOutput = response.getContent();
            logger.info("=== LLM Response (Iteration {}) ===\n{}", i + 1, llmOutput);

            llmCallCount++;
            LLMResponse.TokenUsage tokenUsage = response.getTokenUsage();
            if (tokenUsage != null) {
                totalPromptTokens += tokenUsage.getPromptTokens();
                totalCompletionTokens += tokenUsage.getCompletionTokens();
                totalTokens += tokenUsage.getTotalTokens();
            }
            if (response.getMetadata() != null) {
                if (Boolean.TRUE.equals(response.getMetadata().get(BudgetedLLMClient.ESTIMATED))) usageEstimated = true;
                if (response.getMetadata().get(BudgetedLLMClient.COST) instanceof java.math.BigDecimal c) {
                    totalCost = totalCost == null ? c : totalCost.add(c);
                }
            }

            try {
                Map<String, Object> responseJson;
                try {
                    responseJson = parseResponse(llmOutput);
                } catch (Exception e) {
                    // Fallback: If parsing fails, treat the entire output as the final answer.
                    // The model didn't follow the expected response protocol at all here.
                    logger.warn(
                            "Failed to parse JSON, treating output as final answer: {}",
                            e.getMessage());
                    protocolFollowed = false;
                    responseJson = new HashMap<>();
                    responseJson.put("final_answer", llmOutput);
                    responseJson.put(
                            "thought", "The model responded directly without JSON format.");
                }

                if (!responseJson.containsKey("final_answer")
                        && !responseJson.containsKey("action")
                        && !responseJson.containsKey("thought")) {
                    // ("plan" alone is not the protocol: a structured answer may well have a "plan" field)
                    // A bare JSON payload (e.g. a structured-output reply) is the answer itself.
                    responseJson = new HashMap<>(Map.of(
                            "final_answer", objectMapper.writeValueAsString(responseJson)));
                }

                if (responseJson.containsKey("final_answer")) {
                    String finalAnswer = asAnswerText(responseJson.get("final_answer"));
                    String thought = noteOf(responseJson);
                    return processFinalAnswer(
                            question,
                            finalAnswer,
                            thought,
                            steps,
                            i,
                            new AgentResult.Usage(
                                    llmCallCount, totalPromptTokens, totalCompletionTokens, totalTokens,
                                    usageEstimated, totalCost),
                            redundantActionCount.get(),
                            protocolFollowed);
                }

                String thought = noteOf(responseJson);
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
                if (thought != null) {
                    notifyThought(thought);
                    lastThought = thought;
                }
                if (action != null) notifyAction(action, actionInput);

                if (action == null || action.isEmpty()) {
                    scratchpad.append(llmOutput).append("\nObservation: No valid action found.\n");
                    continue;
                }

                ActionExecution execution =
                        executeAction(action, actionInput, actionHistory, thought, redundantActionCount);
                String observation = execution.observation();
                lastObservation = observation;

                AgentResult.AgentStep step =
                        new AgentResult.AgentStep(
                                thought, action, actionInput, observation, execution.outcome());
                steps.add(step);

                scratchpad.append("Plan: ").append(thought != null ? thought : "").append("\n");
                scratchpad.append("Action: ").append(action).append("\n");
                scratchpad
                        .append("Action Input: ")
                        .append(actionInput != null ? actionInput : "")
                        .append("\n");
                scratchpad.append("Observation: ").append(observation).append("\n");

            } catch (AgentInterrupt interrupt) {
                throw interrupt;
            } catch (Exception e) {
                logger.error("Critical error in agent loop: {}", e.getMessage(), e);
                scratchpad
                        .append(llmOutput)
                        .append("\nObservation: System Error: ")
                        .append(e.getMessage())
                        .append("\n");
            }
        }
        return buildFailureResult(
                steps,
                new AgentResult.Usage(
                        llmCallCount, totalPromptTokens, totalCompletionTokens, totalTokens,
                        usageEstimated, totalCost),
                redundantActionCount.get(),
                protocolFollowed);
    }


    /** The tools as offered to a model that supports native tool calling. */
    private List<ToolSpec> toolSpecs() {
        List<ToolSpec> specs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            specs.add(new ToolSpec(tool.getName(), tool.getDescription(), tool.getParametersSchema()));
        }
        return specs;
    }

    /** What a tool with no declared parameters is sent as: a free-form {@code input} string, which may hold a JSON object. */
    private static Map<String, Object> unwrapFreeForm(Tool tool, Map<String, Object> args) {
        Object declared = tool.getParametersSchema().get("properties");
        boolean none = !(declared instanceof Map<?, ?> m) || m.isEmpty();
        if (!none || args.size() != 1 || !(args.get("input") instanceof String text)) return args;
        String t = text.trim();
        if (!t.startsWith("{")) return args;
        try {
            return objectMapper.readValue(t, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException notJson) {
            return args;
        }
    }

    /** Running totals of a run, shared by the loop's exits. */
    private static final class Tally {
        int calls;
        int prompt;
        int completion;
        int total;
        boolean estimated;
        java.math.BigDecimal cost;

        void add(LLMResponse response) {
            calls++;
            LLMResponse.TokenUsage u = response.getTokenUsage();
            if (u != null) {
                prompt += u.getPromptTokens();
                completion += u.getCompletionTokens();
                total += u.getTotalTokens();
            }
            if (response.getMetadata() != null) {
                if (Boolean.TRUE.equals(response.getMetadata().get(BudgetedLLMClient.ESTIMATED))) estimated = true;
                if (response.getMetadata().get(BudgetedLLMClient.COST) instanceof java.math.BigDecimal c) cost = cost == null ? c : cost.add(c);
            }
        }

        AgentResult.Usage usage() {
            return new AgentResult.Usage(calls, prompt, completion, total, estimated, cost);
        }
    }

    /**
     * The loop on native tool calling: the model gets the tools as definitions and answers with tool calls; each result goes back as a tool
     * message, and a reply with no tool calls is the final answer. Approvals, budgets, rate limits, duplicate blocking, listeners, audit and the
     * result are the same as on the text protocol.
     */
    private AgentResult runNative(String question) {
        List<AgentResult.AgentStep> steps = new ArrayList<>();
        Set<String> actionHistory = new HashSet<>();
        AtomicInteger redundantActionCount = new AtomicInteger(0);
        Tally tally = new Tally();
        String lastThought = null;
        String lastObservation = null;
        int callNumber = 0;

        String context = "";
        if (conversationHistory != null) {
            context += "Previous conversation history:\n" + conversationHistory.getFormattedHistory() + "\n\n";
        }
        if (semanticMemoryService != null) {
            String memoryQuery = question.length() > 500 ? question.substring(0, 500) : question;
            List<String> facts = semanticMemoryService.recallRelevantFacts(memoryQuery, recallTopK, recallMinSimilarity);
            if (!facts.isEmpty()) {
                context += "Relevant context from user's long-term memory:\n";
                for (String fact : facts) context += "- " + fact + "\n";
                context += "\n";
            }
        }
        List<Message> conversation = new ArrayList<>();
        conversation.add(Message.system(nativePrompt));
        conversation.add(Message.user(context + question));
        List<ToolSpec> specs = toolSpecs();

        for (int i = 0; i < maxIterations; i++) {
            logger.debug("Agent iteration {}/{} (native tool calling)", i + 1, maxIterations);
            LLMRequest request = LLMRequest.builder().messages(conversation).tools(specs).temperature(temperature).build();
            LLMResponse response;
            try {
                response = llmClient.chat(request);
            } catch (io.github.llm4j.exception.RateLimitException limited) {
                throw new io.github.llm4j.ratelimit.RateLimited(
                        limited.infoOrEstimate(java.time.Instant.now(), java.time.Duration.ofSeconds(60)),
                        io.github.llm4j.ratelimit.RateLimited.Reason.PROVIDER_LIMIT);
            } catch (BudgetExceeded exhausted) {
                if (budgetPolicy == BudgetPolicy.FAIL) throw exhausted;
                if (budgetPolicy == BudgetPolicy.SUSPEND && exhausted.resetAt().isPresent()) {
                    throw io.github.llm4j.ratelimit.RateLimited.of(exhausted);
                }
                return buildBudgetExhaustedResult(
                        exhausted,
                        lastThought != null ? lastThought : lastObservation != null ? lastObservation : "",
                        steps, i, tally.usage(), redundantActionCount.get(), true);
            }
            tally.add(response);
            String text = response.getContent() == null ? "" : response.getContent();
            logger.info("=== LLM Response (Iteration {}) ===\n{}{}", i + 1, text, response.hasToolCalls() ? "\n[tool calls: " + response.getToolCalls().size() + "]" : "");

            if (!response.hasToolCalls()) {
                if (text.isBlank()) {
                    // an empty reply is not an answer: say so and let the model try again (this counts as an iteration)
                    conversation.add(Message.user("Your last reply was empty. Call a tool, or give your final answer."));
                    continue;
                }
                return processFinalAnswer(question, text, null, steps, i, tally.usage(), redundantActionCount.get(), true);
            }

            String thought = text.isBlank() ? null : text.trim();
            if (thought != null) {
                notifyThought(thought);
                lastThought = thought;
            }
            List<ToolCall> calls = new ArrayList<>();
            for (ToolCall c : response.getToolCalls()) calls.add(c.id() == null || c.id().isBlank() ? c.withId("call_" + (++callNumber)) : c);
            conversation.add(Message.assistantToolCalls(text, calls, response.getProviderData()));

            for (ToolCall call : calls) {
                Tool tool = tools.get(call.name().toLowerCase());
                Map<String, Object> args = tool == null ? call.arguments() : unwrapFreeForm(tool, call.arguments());
                String actionInput;
                try {
                    actionInput = objectMapper.writeValueAsString(args);
                } catch (JsonProcessingException e) {
                    actionInput = String.valueOf(args);
                }
                notifyAction(call.name(), actionInput);
                ActionExecution execution = executeAction(call.name(), actionInput, actionHistory, thought, redundantActionCount);
                lastObservation = execution.observation();
                steps.add(new AgentResult.AgentStep(thought, call.name(), actionInput, execution.observation(), execution.outcome()));
                conversation.add(Message.toolResult(call.id(), call.name(), execution.observation()));
                thought = null; // the model's note belongs to the first call of the turn
            }
        }
        return buildFailureResult(steps, tally.usage(), redundantActionCount.get(), true);
    }

    /**
     * Models asked for structured output often put a JSON object (rather than a string) in
     * {@code final_answer}; keep it as JSON text instead of failing the iteration.
     */
    private static String asAnswerText(Object finalAnswer) throws JsonProcessingException {
        if (finalAnswer == null) return "";
        if (finalAnswer instanceof String text) return text;
        return objectMapper.writeValueAsString(finalAnswer);
    }

    /**
     * The model's note on its next step. The prompt asks for {@code "plan"}: asking current Claude
     * models (Opus 5.5 and later) to fill in a {@code "thought"} field is refused as reasoning
     * extraction. {@code "thought"} is still read, for older prompts and models.
     */
    private static String noteOf(Map<String, Object> response) {
        Object note = response.containsKey("plan") ? response.get("plan") : response.get("thought");
        return note == null ? null : String.valueOf(note);
    }

    private Map<String, Object> parseResponse(String llmOutput) throws Exception {
        Matcher jsonMatcher = JSON_PATTERN.matcher(llmOutput);
        if (jsonMatcher.find()) {
            String jsonBlock = jsonMatcher.group(1);
            return objectMapper.readValue(jsonBlock, new TypeReference<>() {});
        }

        // A bare JSON object that follows the protocol (no fence)
        String trimmed = llmOutput.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                Map<String, Object> bare = objectMapper.readValue(trimmed, new TypeReference<>() {});
                if (bare.containsKey("action") || bare.containsKey("final_answer")) return bare;
            } catch (JsonProcessingException notProtocolJson) {
                // fall through to the line formats
            }
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

    /** Pairs a step's observation text with what actually happened, for {@link AgentResult.AgentStep}. */
    private record ActionExecution(String observation, AgentResult.StepOutcome outcome) {}

    private ActionExecution executeAction(
            String action,
            String actionInput,
            Set<String> actionHistory,
            String thought,
            AtomicInteger redundantActionCount) {
        String actionKey = action + ":" + (actionInput != null ? actionInput : "");
        if (actionHistory.contains(actionKey)) {
            logger.warn("Loop detected: {}", actionKey);
            redundantActionCount.incrementAndGet();
            return new ActionExecution(
                    "Error: You have already taken this action with this input. Please try a different approach.",
                    AgentResult.StepOutcome.DUPLICATE_BLOCKED);
        }
        actionHistory.add(actionKey);

        Tool tool = tools.get(action.toLowerCase());
        if (tool == null) {
            logger.warn("Unknown tool: {}", action);
            return new ActionExecution(
                    "Error: Unknown tool '"
                            + action
                            + "'. Available tools: "
                            + String.join(", ", tools.keySet()),
                    AgentResult.StepOutcome.UNKNOWN_TOOL);
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
                    return new ActionExecution(
                            "Error: Action '" + action
                                    + "' requires human approval, but no ApprovalCallback is configured."
                                    + " Add one via ReActAgent.Builder#approvalCallback().",
                            AgentResult.StepOutcome.APPROVAL_UNAVAILABLE);
                }
                boolean approved = approvalCallback.approve(action, args, thought != null ? thought : "");
                if (!approved) {
                    logger.info("Human rejected action '{}'. Feeding back to agent.", action);
                    return new ActionExecution(
                            "Observation: A human supervisor rejected this action. "
                                    + "Do not attempt it again. Choose a different approach to accomplish the goal.",
                            AgentResult.StepOutcome.REJECTED_BY_HUMAN);
                }
                logger.info("Human approved action '{}'.", action);
            }
            // ─────────────────────────────────────────────────────────────────

            String observation = tool.execute(args);
            logger.info("Tool '{}' returned observation: {}", action, observation);
            try {
                auditLogger.logToolExecution(sessionId, action, String.valueOf(args), observation, java.time.Instant.now());
            } catch (RuntimeException auditFailure) {
                logger.warn("Audit logging of tool '{}' failed: {}", action, auditFailure.getMessage());
            }
            notifyObservation(observation);
            return new ActionExecution(observation, AgentResult.StepOutcome.EXECUTED);
        } catch (AgentInterrupt interrupt) {
            throw interrupt; // a deliberate stop (e.g. waiting for a human) is never a tool error
        } catch (Exception e) {
            logger.error("Error executing tool {}: {}", action, e.getMessage(), e);
            return new ActionExecution(
                    "Error executing tool: " + e.getMessage(), AgentResult.StepOutcome.EXECUTION_ERROR);
        }
    }

    private AgentResult processFinalAnswer(
            String question,
            String finalAnswer,
            String thought,
            List<AgentResult.AgentStep> steps,
            int iteration,
            AgentResult.Usage usage,
            int redundantActionCount,
            boolean protocolFollowed) {
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
                        .usage(usage)
                        .redundantActionCount(redundantActionCount)
                        .protocolFollowed(protocolFollowed)
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

    /** The budget ran out mid-task: return the best answer so far, clearly marked. */
    private AgentResult buildBudgetExhaustedResult(
            BudgetExceeded exhausted,
            String bestSoFar,
            List<AgentResult.AgentStep> steps,
            int iteration,
            AgentResult.Usage usage,
            int redundantActionCount,
            boolean protocolFollowed) {
        logger.warn("Agent stopped: {}", exhausted.getMessage());
        List<AgentResult.AgentStep> all = new ArrayList<>(steps);
        all.add(new AgentResult.AgentStep(null, null, null, exhausted.getMessage(),
                AgentResult.StepOutcome.BUDGET_EXHAUSTED));
        AgentResult result =
                AgentResult.builder()
                        .finalAnswer(bestSoFar)
                        .steps(all)
                        .iterations(iteration)
                        .completed(false)
                        .budgetExceeded(exhausted)
                        .confidence(ConfidenceScore.low(exhausted.getMessage()))
                        .uncertaintyDetected(true)
                        .uncertaintyReason(exhausted.getMessage())
                        .usage(usage)
                        .redundantActionCount(redundantActionCount)
                        .protocolFollowed(protocolFollowed)
                        .build();
        auditLogger.logAgentDecision(
                AuditEvent.builder().sessionId(sessionId).agentResult(result).build());
        return result;
    }

    private AgentResult buildFailureResult(
            List<AgentResult.AgentStep> steps,
            AgentResult.Usage usage,
            int redundantActionCount,
            boolean protocolFollowed) {
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
                        .usage(usage)
                        .redundantActionCount(redundantActionCount)
                        .protocolFollowed(protocolFollowed)
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
        String injected = resolveTemplatedPrompt(builder);
        if (builder.instructions != null && !builder.instructions.isBlank()) {
            return builder.instructions.trim() + "\n\n" + injected;
        }
        return injected;
    }

    private String resolveTemplatedPrompt(Builder builder) {
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

    private static boolean isFailure(AgentResult.StepOutcome outcome) {
        return switch (outcome) {
            case EXECUTION_ERROR, UNKNOWN_TOOL, DUPLICATE_BLOCKED, APPROVAL_UNAVAILABLE -> true;
            default -> false;
        };
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
        // What happened, not what the text says: a tool may legitimately return text that begins "Error".
        long failureCount =
                steps.stream()
                        .filter(step -> step.getOutcome() != null && isFailure(step.getOutcome()))
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
        private String instructions;
        private int maxIterations = 10;
        private double temperature = 0.7;
        private AgentPersona persona;
        private List<AgentSkill> skills = new ArrayList<>();
        private PromptRegistry promptRegistry;
        private String systemPromptId;
        private ConversationHistory conversationHistory;
        private SemanticMemoryService semanticMemoryService;
        private int recallTopK = 5;
        private float recallMinSimilarity = 0.7f;
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
        private Budget budget;
        private Integer maxTokensPerCall;
        private BudgetPolicy budgetPolicy = BudgetPolicy.RETURN_PARTIAL;
        private PriceTable priceTable;
        private String budgetModel;
        private TokenEstimator tokenEstimator;
        private ToolCalling toolCalling = ToolCalling.AUTO;
        private boolean inheritedPrompt;
        private String inheritedNativePrompt;

        private Builder() {}

        private Builder(ReActAgent agent) {
            this.llmClient = agent.baseClient;
            this.budget = agent.budget;
            this.maxTokensPerCall = agent.maxTokensPerCall;
            this.budgetPolicy = agent.budgetPolicy;
            this.priceTable = agent.priceTable;
            this.budgetModel = agent.budgetModel;
            this.tokenEstimator = agent.tokenEstimator;
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
            this.recallTopK = agent.recallTopK;
            this.recallMinSimilarity = agent.recallMinSimilarity;
            this.listeners = new ArrayList<>(agent.listeners);
            this.auditLogger = agent.auditLogger;
            this.sessionId = agent.sessionId;
            this.biasMonitor = agent.biasMonitor;
            this.ttsProvider = agent.ttsProvider;
            this.approvalCallback = agent.approvalCallback;

            this.sttProvider = agent.sttProvider;
            this.audioPlayer = agent.audioPlayer;
            this.autoPlayAudio = agent.autoPlayAudio;
            this.ttsLanguage = agent.ttsLanguage;
            this.ttsModel = agent.ttsModel;
            this.toolCalling = agent.toolCalling;
            this.inheritedPrompt = !agent.customPrompt;
            this.inheritedNativePrompt = agent.nativePrompt;
        }

        /** How the agent asks the model to use tools; {@link ToolCalling#AUTO} by default. */
        public Builder toolCalling(ToolCalling mode) {
            this.toolCalling = mode == null ? ToolCalling.AUTO : mode;
            return this;
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
            this.inheritedPrompt = false;
            return this;
        }

        /**
         * Role-specific instructions placed ahead of the default ReAct protocol. Unlike
         * {@link #systemPrompt(String)}, which replaces the whole prompt verbatim, this keeps the
         * tool descriptions and JSON response format, so tool-using agents can be given a role
         * without re-stating the protocol. Ignored when {@code systemPrompt} is set.
         */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
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
            this.inheritedPrompt = false;
            return this;
        }

        public Builder conversationHistory(ConversationHistory history) {
            this.conversationHistory = history;
            return this;
        }

        /**
         * How many long-term facts are recalled before a task, and how similar (0–1) a fact must be to
         * the task to be included. Defaults: 5 and 0.7.
         */
        public Builder semanticRecall(int topK, float minSimilarity) {
            if (topK < 1) throw new IllegalArgumentException("topK must be positive");
            if (minSimilarity < 0 || minSimilarity > 1) throw new IllegalArgumentException("minSimilarity must be between 0 and 1");
            this.recallTopK = topK;
            this.recallMinSimilarity = minSimilarity;
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

        /** Caps what this agent may spend; see {@link Budget}. */
        public Builder budget(Budget budget) {
            this.budget = budget;
            return this;
        }

        /** Caps every answer's output tokens. */
        public Builder maxTokensPerCall(int maxTokens) {
            if (maxTokens <= 0) throw new IllegalArgumentException("maxTokensPerCall must be positive");
            this.maxTokensPerCall = maxTokens;
            return this;
        }

        /** What to do when the budget runs out mid-task. Default {@link BudgetPolicy#RETURN_PARTIAL}. */
        public Builder onBudgetExhausted(BudgetPolicy policy) {
            this.budgetPolicy = Objects.requireNonNull(policy);
            return this;
        }

        /** Prices for cost budgets and cost reporting. */
        public Builder priceTable(PriceTable prices) {
            this.priceTable = prices;
            return this;
        }

        /** The model id to price calls with, when requests don't name one. */
        public Builder budgetModel(String model) {
            this.budgetModel = model;
            return this;
        }

        public Builder tokenEstimator(TokenEstimator estimator) {
            this.tokenEstimator = estimator;
            return this;
        }

        public ReActAgent build() {
            return new ReActAgent(this);
        }
    }
}
