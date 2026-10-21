package io.github.llm4j.provider.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.*;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.provider.DescribableProvider;
import io.github.llm4j.provider.Providers;
import java.io.IOException;
import java.util.Objects;
import java.util.stream.Stream;
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GoogleProvider implements DescribableProvider {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Key in {@link io.github.llm4j.model.LLMResponse#getProviderData()}: the parts of a tool-call
     * reply, to be sent back verbatim.
     */
    static final String RAW_PARTS = "google.parts";

    private static final Logger logger = LoggerFactory.getLogger(GoogleProvider.class);
    private static final String DEFAULT_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta";

    private final LLMConfig config;
    private final HttpClientWrapper httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public GoogleProvider(LLMConfig config) {
        this(config, new HttpClientWrapper(config), new ObjectMapper());
    }

    public GoogleProvider(
            LLMConfig config, HttpClientWrapper httpClient, ObjectMapper objectMapper) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URL;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        validate();
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        try {
            String model =
                    request.getModel() != null ? request.getModel() : config.getDefaultModel();
            if (model == null) {
                throw new InvalidRequestException("Model must be specified in request or config");
            }

            String endpoint = String.format("/models/%s:generateContent", model);
            String url = baseUrl + endpoint;
            String requestJson = buildRequestJson(request, false);
            Headers headers = buildHeaders();

            logger.debug("Calling Google API URL: {}", url);
            String responseJson = httpClient.post(url, requestJson, headers);
            return parseResponse(responseJson, model);
        } catch (ProviderException e) {
            throw e;
        } catch (io.github.llm4j.exception.RateLimitException e) {
            throw e;
        } catch (IOException | LLMException e) {
            throw typed("Failed to process request", e);
        }
    }

    /**
     * Streams through {@code :streamGenerateContent?alt=sse}: each event is a partial response; its
     * text parts become chunks, and the finish reason and usage (sent with the last event) the
     * final chunk.
     */
    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        String model = request.getModel() != null ? request.getModel() : config.getDefaultModel();
        if (model == null) {
            throw new InvalidRequestException("Model must be specified in request or config");
        }
        String url = baseUrl + String.format("/models/%s:streamGenerateContent?alt=sse", model);
        io.github.llm4j.http.StreamingBody body;
        try {
            body = httpClient.stream(url, buildRequestJson(request, true), buildHeaders());
        } catch (IOException | LLMException e) {
            throw typed("Failed to start streaming", e);
        }
        String[] finish = {null};
        Integer[] usage = {null, null};
        int[] calls = {0}; // tool calls seen so far, to number the next
        java.util.List<JsonNode> allParts =
                new java.util
                        .ArrayList<>(); // every part, as Gemini sent it, to send back with tool
        // results
        Stream<LLMResponse> text =
                body.events()
                        .flatMap(
                                event -> {
                                    JsonNode root;
                                    try {
                                        root = objectMapper.readTree(event.data());
                                    } catch (IOException e) {
                                        throw new ProviderException(
                                                getProviderName(),
                                                "Unreadable stream event: " + event.data(),
                                                e);
                                    }
                                    if (root.has("error")) throw streamError(root.get("error"));
                                    JsonNode candidate = root.path("candidates").path(0);
                                    String reason = candidate.path("finishReason").asText(null);
                                    if (reason != null) finish[0] = reason;
                                    if (isBlocked(reason)) {
                                        throw new ContentBlockedException(
                                                getProviderName(),
                                                "Content blocked by safety filters ("
                                                        + reason
                                                        + ")");
                                    }
                                    JsonNode u = root.path("usageMetadata");
                                    if (!u.isMissingNode()) {
                                        usage[0] = u.path("promptTokenCount").asInt(0);
                                        usage[1] =
                                                u.path("candidatesTokenCount").asInt(0)
                                                        + u.path("thoughtsTokenCount").asInt(0);
                                    }
                                    JsonNode parts = candidate.path("content").path("parts");
                                    if (parts.isArray()) parts.forEach(allParts::add);
                                    return chunksOf(parts, model, request.isReasoning(), calls)
                                            .stream();
                                });
        return Stream.concat(
                        text,
                        Stream.of(0)
                                .map(
                                        x -> {
                                            java.util.Map<String, Object> raw = java.util.Map.of();
                                            if (calls[0] > 0) {
                                                raw =
                                                        java.util.Map.of(
                                                                RAW_PARTS,
                                                                objectMapper.convertValue(
                                                                        allParts,
                                                                        new com.fasterxml.jackson
                                                                                        .core.type
                                                                                        .TypeReference<
                                                                                java.util.List<
                                                                                        java.util
                                                                                                        .Map<
                                                                                                String,
                                                                                                Object>>>() {}));
                                            }
                                            return Providers.finalChunk(
                                                    finish[0], usage[0], usage[1], model, raw);
                                        }))
                .onClose(body::close);
    }

    /**
     * As {@link Providers#typed}, plus one Gemini quirk: a bad API key comes back as 400
     * INVALID_ARGUMENT (reason API_KEY_INVALID), not 401 — the contract calls it an authentication
     * failure whichever provider it is.
     */
    private RuntimeException typed(String message, Exception e) {
        if (e instanceof InvalidRequestException invalid
                && invalid.getResponseBody() != null
                && (invalid.getResponseBody().contains("API_KEY_INVALID")
                        || invalid.getResponseBody().contains("API key not valid"))) {
            return new AuthenticationException(
                    invalid.getMessage(), invalid.getStatusCode(), invalid.getResponseBody());
        }
        return Providers.typed(getProviderName(), message, e);
    }

    private static boolean isBlocked(String finishReason) {
        return finishReason != null
                && LLMResponse.FinishReason.fromValue(finishReason)
                        == LLMResponse.FinishReason.CONTENT_FILTER;
    }

    private RuntimeException streamError(JsonNode error) {
        int code = error.path("code").asInt(500);
        String message =
                error.path("status").asText("error") + ": " + error.path("message").asText("");
        if (code == 401 || code == 403) return new AuthenticationException(message);
        if (code == 400 || code == 404)
            return io.github.llm4j.context.ContextOverflowClassifier.invalidRequest(
                    message, code, error.toString());
        if (code == 429) return new io.github.llm4j.exception.RateLimitException(message);
        if (code >= 500)
            return new io.github.llm4j.exception.ServiceUnavailableException(
                    getProviderName(), message, code, error.toString());
        return new ProviderException(getProviderName(), message, code);
    }

    /**
     * One streamed event's parts as chunks, in order: consecutive answer-text parts joined into one
     * text chunk, thought parts as reasoning chunks (only when asked for), and each function call
     * whole, as one tool-call chunk.
     */
    private static java.util.List<LLMResponse> chunksOf(
            JsonNode parts, String model, boolean reasoning, int[] calls) {
        java.util.List<LLMResponse> out = new java.util.ArrayList<>();
        if (!parts.isArray()) return out;
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.path("thought").asBoolean(false)) {
                if (reasoning && part.hasNonNull("text") && !part.get("text").asText().isEmpty()) {
                    flush(text, model, out);
                    out.add(Providers.reasoningChunk(part.get("text").asText(), model));
                }
            } else if (part.has("functionCall")) {
                flush(text, model, out);
                JsonNode fc = part.get("functionCall");
                String args = fc.has("args") ? fc.get("args").toString() : "{}";
                out.add(
                        Providers.toolCallChunk(
                                new io.github.llm4j.model.ToolCallDelta(
                                        calls[0]++,
                                        fc.path("id").asText(null),
                                        fc.path("name").asText(),
                                        args),
                                model));
            } else if (part.has("text")) {
                text.append(part.get("text").asText());
            }
        }
        flush(text, model, out);
        return out;
    }

    private static void flush(StringBuilder text, String model, java.util.List<LLMResponse> out) {
        if (text.length() == 0) return;
        out.add(Providers.textChunk(text.toString(), model));
        text.setLength(0);
    }

    /** The answer's text: every text part except the model's thoughts. */
    private static String answerText(JsonNode parts) {
        StringBuilder sb = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.path("thought").asBoolean(false)) continue;
                if (part.has("text")) sb.append(part.get("text").asText());
            }
        }
        return sb.toString();
    }

    @Override
    public String getProviderName() {
        return "google";
    }

    @Override
    public void validate() {
        if (!config.hasApiKey()) {
            throw new AuthenticationException(
                    config.missingApiKeyMessage("Google API key is required"));
        }
    }

    @Override
    public String[] listModels() {
        try {
            String url = baseUrl + "/models";
            String responseJson = httpClient.get(url, buildHeaders());
            JsonNode root = objectMapper.readTree(responseJson);

            if (root.has("models")) {
                return objectMapper.convertValue(root.get("models"), String[].class);
            }
            return new String[0];
        } catch (ProviderException e) {
            throw e;
        } catch (io.github.llm4j.exception.RateLimitException e) {
            throw e;
        } catch (IOException | LLMException e) {
            logger.error("Failed to list models from Google API", e);
            throw typed("Failed to list models", e);
        }
    }

    @Override
    public String getFirstAvailableModel() {
        try {
            String url = baseUrl + "/models";
            String responseJson = httpClient.get(url, buildHeaders());
            JsonNode root = objectMapper.readTree(responseJson);

            if (root.has("models")) {
                for (JsonNode model : root.get("models")) {
                    String modelName = model.path("name").asText();
                    String modelId = modelName.substring(modelName.lastIndexOf('/') + 1);

                    JsonNode methods = model.path("supportedGenerationMethods");
                    for (JsonNode method : methods) {
                        if ("generateContent".equals(method.asText())
                                && modelId.contains("gemini")) {
                            return modelId;
                        }
                    }
                }
            }
            return null;
        } catch (ProviderException e) {
            throw e;
        } catch (io.github.llm4j.exception.RateLimitException e) {
            throw e;
        } catch (IOException | LLMException e) {
            logger.error("Failed to get available models from Google API", e);
            throw typed("Failed to get available models", e);
        }
    }

    private String buildRequestJson(LLMRequest request, boolean stream) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contentsArray = root.putArray("contents");

        // System messages go in Gemini's own systemInstruction, joined in order.
        String system =
                request.getMessages().stream()
                        .filter(m -> m.getRole() == Message.Role.SYSTEM)
                        .map(Message::getContent)
                        .collect(java.util.stream.Collectors.joining("\n\n"));
        if (!system.isEmpty()) {
            root.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        }

        // Gemini expects user and model turns to alternate: consecutive turns of one role are
        // merged.
        // An assistant tool call is a model turn of functionCall parts; a tool result is a user
        // turn of functionResponse parts.
        String lastRole = null;
        ArrayNode lastParts = null;
        for (Message message : request.getMessages()) {
            if (message.getRole() == Message.Role.SYSTEM) {
                continue;
            }
            String role = message.getRole() == Message.Role.ASSISTANT ? "model" : "user";
            if (!role.equals(lastRole)) {
                ObjectNode contentNode = contentsArray.addObject();
                contentNode.put("role", role);
                lastParts = contentNode.putArray("parts");
                lastRole = role;
            }
            addParts(lastParts, message);
        }

        if (!request.getTools().isEmpty()) {
            ArrayNode declarations =
                    root.putArray("tools").addObject().putArray("functionDeclarations");
            for (ToolSpec t : request.getTools()) {
                ObjectNode d =
                        declarations
                                .addObject()
                                .put("name", t.name())
                                .put("description", t.description());
                d.set("parameters", geminiSchema(t.parameters()));
            }
            root.putObject("toolConfig")
                    .putObject("functionCallingConfig")
                    .put(
                            "mode",
                            request.getToolChoice() == LLMRequest.ToolChoice.NONE
                                    ? "NONE"
                                    : "AUTO");
        }

        ObjectNode generationConfig = root.putObject("generationConfig");
        if (request.getTemperature() != null)
            generationConfig.put("temperature", request.getTemperature());
        if (request.getMaxTokens() != null)
            generationConfig.put("maxOutputTokens", request.getMaxTokens());
        if (request.getTopP() != null) generationConfig.put("topP", request.getTopP());
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            ArrayNode stopArray = generationConfig.putArray("stopSequences");
            request.getStopSequences().forEach(stopArray::add);
        }
        // thoughts come back as parts flagged "thought"; without this they are not sent at all
        if (stream && request.isReasoning())
            generationConfig.putObject("thinkingConfig").put("includeThoughts", true);

        return objectMapper.writeValueAsString(root);
    }

    private void addParts(ArrayNode parts, Message message) {
        if (message.getRole() == Message.Role.TOOL) {
            ObjectNode response = parts.addObject().putObject("functionResponse");
            response.put("name", message.getName() == null ? "" : message.getName());
            response.putObject("response").put("result", message.getContent());
            return;
        }
        if (message.getRole() == Message.Role.ASSISTANT && !message.getToolCalls().isEmpty()) {
            Object raw = message.getProviderData().get(RAW_PARTS);
            if (raw instanceof java.util.List<?> list && !list.isEmpty()) {
                // the parts Gemini produced (thought signatures included), sent back untouched
                for (Object part : list) parts.add(objectMapper.valueToTree(part));
                return;
            }
            if (!message.getContent().isEmpty())
                parts.addObject().put("text", message.getContent());
            for (ToolCall c : message.getToolCalls()) {
                ObjectNode call = parts.addObject().putObject("functionCall");
                call.put("name", c.name());
                call.set("args", objectMapper.valueToTree(c.arguments()));
            }
            return;
        }
        parts.addObject().put("text", message.getContent());
    }

    /**
     * Gemini accepts an OpenAPI subset: {@code additionalProperties} and a few other keywords are
     * refused, and an object with no properties is refused too, so a tool that declares none is
     * offered one free-form {@code input} string (the agent unwraps it).
     */
    static JsonNode geminiSchema(java.util.Map<String, Object> schema) {
        JsonNode node = MAPPER.valueToTree(schema);
        node = stripUnsupported(node);
        if (node.isObject()
                && "object".equals(node.path("type").asText())
                && node.path("properties").size() == 0) {
            ObjectNode free = MAPPER.createObjectNode().put("type", "object");
            free.putObject("properties")
                    .putObject(FREE_FORM_INPUT)
                    .put("type", "string")
                    .put(
                            "description",
                            "The tool's input. For several arguments, a JSON object written as a string, as the tool description says.");
            return free;
        }
        return node;
    }

    /**
     * The argument name a tool with no declared parameters is offered (see {@link #geminiSchema}).
     */
    public static final String FREE_FORM_INPUT = "input";

    private static JsonNode stripUnsupported(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            node.fields()
                    .forEachRemaining(
                            e -> {
                                String k = e.getKey();
                                if (k.equals("additionalProperties")
                                        || k.equals("$schema")
                                        || k.equals("$id")
                                        || k.equals("examples")
                                        || k.equals("default")) return;
                                out.set(k, stripUnsupported(e.getValue()));
                            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(n -> out.add(stripUnsupported(n)));
            return out;
        }
        return node;
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    private Headers buildHeaders() {
        return new Headers.Builder()
                .add("Content-Type", "application/json")
                .add("x-goog-api-key", config.requireApiKey("Google", baseUrl))
                .build();
    }

    private LLMResponse parseResponse(String responseJson, String model) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);

        if (root.has("error")) {
            JsonNode error = root.get("error");
            String message = error.path("message").asText("Unknown error");
            int code = error.path("code").asInt(500);
            if (code == 401 || code == 403) throw new AuthenticationException(message);
            if (code == 400)
                throw io.github.llm4j.context.ContextOverflowClassifier.invalidRequest(
                        message, code, error.toString());
            throw new ProviderException(getProviderName(), message, code);
        }

        JsonNode candidates = root.path("candidates");
        if (candidates.isMissingNode() || !candidates.isArray() || candidates.isEmpty()) {
            JsonNode feedback = root.path("promptFeedback");
            if (feedback.has("blockReason")) {
                throw new ContentBlockedException(
                        getProviderName(),
                        "Content blocked by safety filters: "
                                + feedback.get("blockReason").asText());
            }
            throw new ProviderException(
                    getProviderName(), "No candidates in response: " + responseJson);
        }

        JsonNode candidate = candidates.get(0);
        String finishReason = candidate.path("finishReason").asText(null);

        if (isBlocked(finishReason)) {
            String safetyInfo =
                    candidate.has("safetyRatings")
                            ? " Safety ratings: " + candidate.get("safetyRatings")
                            : "";
            throw new ContentBlockedException(
                    getProviderName(), "Content blocked by safety filters." + safetyInfo);
        }

        JsonNode parts = candidate.path("content").path("parts");
        if (parts.isMissingNode() || !parts.isArray() || parts.isEmpty()) {
            if ("MAX_TOKENS".equals(finishReason)) {
                return LLMResponse.builder()
                        .content(
                                "[Response truncated: model hit token limit before generating output.]")
                        .model(model)
                        .finishReason(finishReason)
                        .addMetadata(Providers.FINISH_REASON_RAW, finishReason)
                        .build();
            }
            throw new ProviderException(
                    getProviderName(),
                    "No parts in response content: " + candidate.path("content"));
        }

        String textContent = answerText(parts);
        java.util.List<ToolCall> calls = new java.util.ArrayList<>();
        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                JsonNode fc = part.get("functionCall");
                calls.add(
                        new ToolCall(
                                fc.path("id").asText(null),
                                fc.path("name").asText(),
                                objectMapper.convertValue(
                                        fc.path("args"),
                                        new com.fasterxml.jackson.core.type.TypeReference<
                                                java.util.Map<String, Object>>() {})));
            }
        }
        java.util.Map<String, Object> rawParts = java.util.Map.of();
        if (!calls.isEmpty()) {
            rawParts =
                    java.util.Map.of(
                            RAW_PARTS,
                            objectMapper.convertValue(
                                    parts,
                                    new com.fasterxml.jackson.core.type.TypeReference<
                                            java.util.List<java.util.Map<String, Object>>>() {}));
        }

        LLMResponse.TokenUsage tokenUsage = null;
        JsonNode usage = root.path("usageMetadata");
        if (!usage.isMissingNode()) {
            tokenUsage =
                    new LLMResponse.TokenUsage(
                            usage.path("promptTokenCount").asInt(0),
                            // thinking tokens are billed as output
                            usage.path("candidatesTokenCount").asInt(0)
                                    + usage.path("thoughtsTokenCount").asInt(0),
                            usage.path("totalTokenCount").asInt(0));
        }

        return LLMResponse.builder()
                .content(textContent)
                .model(model)
                .tokenUsage(tokenUsage)
                .toolCalls(calls)
                .providerData(rawParts)
                .finishReason(calls.isEmpty() ? finishReason : "tool_calls")
                .addMetadata(Providers.FINISH_REASON_RAW, finishReason)
                .build();
    }
}
