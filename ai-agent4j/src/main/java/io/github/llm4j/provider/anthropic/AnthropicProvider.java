package io.github.llm4j.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.ContentBlockedException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.exception.ServiceUnavailableException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.http.StreamingBody;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.Providers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claude, through the Messages API ({@code POST /v1/messages}) over plain HTTP.
 *
 * <p>Anthropic's differences from other providers are handled here, so callers see the common
 * contract: system messages go in the top-level {@code system} field; {@code max_tokens} is always sent
 * (16,000 by default, 64,000 when streaming); {@code temperature} and {@code top_p} are left out for
 * models that reject them ({@link AnthropicModels}); thinking blocks are not part of the answer; a
 * refusal throws {@link ContentBlockedException}; "overloaded" (529) is retried, then reported as
 * {@link ServiceUnavailableException}.
 */
public class AnthropicProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicProvider.class);
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    static final String API_VERSION = "2023-06-01";
    static final int DEFAULT_MAX_TOKENS = 16_000;
    static final int DEFAULT_STREAM_MAX_TOKENS = 64_000;
    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Key in {@link io.github.llm4j.model.LLMResponse#getProviderData()}: the content blocks of a tool-call reply, to be sent back verbatim. */
    static final String RAW_CONTENT = "anthropic.content";

    private final LLMConfig config;
    private final HttpClientWrapper http;
    private final String baseUrl;
    private final String effort;

    public AnthropicProvider(LLMConfig config) {
        this(config, null);
    }

    /**
     * @param effort {@code low} … {@code max}, sent as {@code output_config.effort}; null for the
     *     model's default. A request's {@code additionalParameters.effort} overrides it.
     */
    public AnthropicProvider(LLMConfig config, String effort) {
        this(config, new HttpClientWrapper(config), effort);
    }

    AnthropicProvider(LLMConfig config, HttpClientWrapper http, String effort) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.http = http;
        String url = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URL;
        this.baseUrl = url.replaceAll("/+$", "");
        if (effort != null && !EFFORTS.contains(effort)) {
            throw new IllegalArgumentException("effort must be one of " + EFFORTS + ", got " + effort);
        }
        this.effort = effort;
        validate();
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        String model = model(request);
        try {
            String json = http.post(baseUrl + "/v1/messages", body(request, model, false), headers());
            return parse(JSON.readTree(json), model);
        } catch (IOException | LLMException e) {
            throw Providers.typed(getProviderName(), "Failed to process chat request", e);
        }
    }

    /**
     * Server-sent events: {@code message_start} (model, input usage), {@code content_block_delta} with
     * {@code text_delta} (a chunk), {@code message_delta} (stop reason, output usage), {@code
     * message_stop} (the final chunk), {@code error}. Everything else — pings, thinking deltas — is
     * skipped.
     */
    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        String model = model(request);
        StreamingBody body;
        try {
            body = http.stream(baseUrl + "/v1/messages", body(request, model, true), headers());
        } catch (IOException | LLMException e) {
            throw Providers.typed(getProviderName(), "Failed to start streaming", e);
        }
        String[] answering = {model};
        String[] stop = {null};
        JsonNode[] stopDetails = {null};
        Integer[] usage = {null, null};
        return body.events().flatMap(event -> {
            JsonNode data;
            try {
                data = JSON.readTree(event.data());
            } catch (IOException e) {
                throw new ProviderException(getProviderName(), "Unreadable stream event: " + event.data(), e);
            }
            String type = data.path("type").asText(event.name() == null ? "" : event.name());
            switch (type) {
                case "message_start" -> {
                    JsonNode message = data.path("message");
                    if (message.hasNonNull("model")) answering[0] = message.get("model").asText();
                    usage[0] = inputTokens(message.path("usage"));
                    return Stream.empty();
                }
                case "content_block_delta" -> {
                    JsonNode delta = data.path("delta");
                    if (!"text_delta".equals(delta.path("type").asText())) return Stream.empty();
                    return Stream.of(Providers.textChunk(delta.path("text").asText(""), answering[0]));
                }
                case "message_delta" -> {
                    JsonNode delta = data.path("delta");
                    if (delta.hasNonNull("stop_reason")) stop[0] = delta.get("stop_reason").asText();
                    if (delta.has("stop_details")) stopDetails[0] = delta.get("stop_details");
                    JsonNode u = data.path("usage");
                    if (u.has("output_tokens")) usage[1] = u.get("output_tokens").asInt();
                    if (u.has("input_tokens")) usage[0] = inputTokens(u);
                    return Stream.empty();
                }
                case "message_stop" -> {
                    if ("refusal".equals(stop[0])) throw refusal(stopDetails[0]);
                    return Stream.of(Providers.finalChunk(stop[0], usage[0], usage[1], answering[0]));
                }
                case "error" -> throw streamError(data.path("error"));
                default -> {
                    return Stream.empty();
                }
            }
        }).onClose(body::close);
    }

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    @Override
    public void validate() {
        if (!config.hasApiKey()) {
            throw new AuthenticationException(config.missingApiKeyMessage("Anthropic API key is required (ANTHROPIC_API_KEY)"));
        }
    }

    // ── request ─────────────────────────────────────────────────────────────────────────────

    private String model(LLMRequest request) {
        String model = request.getModel() != null ? request.getModel() : config.getDefaultModel();
        if (model == null || model.isBlank()) {
            throw new InvalidRequestException("Model must be specified in request or config, e.g. claude-opus-5-5");
        }
        return model;
    }

    String body(LLMRequest request, String model, boolean stream) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens()
                : stream ? DEFAULT_STREAM_MAX_TOKENS : DEFAULT_MAX_TOKENS);

        String system = request.getMessages().stream()
                .filter(m -> m.getRole() == Message.Role.SYSTEM)
                .map(Message::getContent)
                .collect(Collectors.joining("\n\n"));
        if (!system.isEmpty()) root.put("system", system);

        // Anthropic has no system role and expects user/assistant turns: merge consecutive turns of one role.
        // A tool result is a user turn holding a tool_result block; an assistant tool call is tool_use blocks.
        List<String> roles = new ArrayList<>();
        List<List<ObjectNode>> blocks = new ArrayList<>();
        for (Message m : request.getMessages()) {
            if (m.getRole() == Message.Role.SYSTEM) continue;
            String role = m.getRole() == Message.Role.ASSISTANT ? "assistant" : "user";
            List<ObjectNode> mine = blocksOf(m);
            if (!roles.isEmpty() && roles.get(roles.size() - 1).equals(role)) {
                blocks.get(blocks.size() - 1).addAll(mine);
            } else {
                roles.add(role);
                blocks.add(new ArrayList<>(mine));
            }
        }
        ArrayNode messages = root.putArray("messages");
        for (int i = 0; i < roles.size(); i++) {
            ObjectNode turn = messages.addObject().put("role", roles.get(i));
            List<ObjectNode> turnBlocks = blocks.get(i);
            if (turnBlocks.stream().allMatch(AnthropicProvider::isText)) {
                // plain text turns keep the simple string form
                turn.put("content", turnBlocks.stream().map(x -> x.path("text").asText()).collect(Collectors.joining("\n\n")));
            } else {
                ArrayNode content = turn.putArray("content");
                turnBlocks.forEach(content::add);
            }
        }

        if (!request.getTools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpec t : request.getTools()) {
                ObjectNode tool = tools.addObject().put("name", t.name()).put("description", t.description());
                tool.set("input_schema", JSON.valueToTree(t.parameters()));
            }
            root.putObject("tool_choice").put("type", request.getToolChoice() == LLMRequest.ToolChoice.NONE ? "none" : "auto");
        }

        if (AnthropicModels.acceptsSampling(model)) {
            if (request.getTemperature() != null) root.put("temperature", request.getTemperature());
            if (request.getTopP() != null) root.put("top_p", request.getTopP());
        } else if (request.getTemperature() != null || request.getTopP() != null) {
            logger.debug("{} doesn't accept temperature/top_p; leaving them out", model);
        }
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            ArrayNode stops = root.putArray("stop_sequences");
            request.getStopSequences().forEach(stops::add);
        }
        Object requested = request.getAdditionalParameters() == null ? null : request.getAdditionalParameters().get("effort");
        String level = requested != null ? String.valueOf(requested) : effort;
        if (level != null) {
            if (!EFFORTS.contains(level)) throw new InvalidRequestException("effort must be one of " + EFFORTS + ", got " + level);
            root.putObject("output_config").put("effort", level);
        }
        if (stream) root.put("stream", true);
        return JSON.writeValueAsString(root);
    }

    private static boolean isText(ObjectNode block) {
        return "text".equals(block.path("type").asText());
    }

    /** The content blocks of one message. An assistant tool-call turn re-sends the blocks Claude produced (thinking included), verbatim. */
    private static List<ObjectNode> blocksOf(Message m) {
        List<ObjectNode> out = new ArrayList<>();
        if (m.getRole() == Message.Role.TOOL) {
            ObjectNode result = JSON.createObjectNode().put("type", "tool_result").put("tool_use_id", m.getToolCallId() == null ? "" : m.getToolCallId());
            result.put("content", m.getContent());
            out.add(result);
            return out;
        }
        if (m.getRole() == Message.Role.ASSISTANT && !m.getToolCalls().isEmpty()) {
            Object raw = m.getProviderData().get(RAW_CONTENT);
            if (raw instanceof List<?> list && !list.isEmpty()) {
                for (Object block : list) out.add((ObjectNode) JSON.valueToTree(block));
                return out;
            }
            if (!m.getContent().isEmpty()) out.add(JSON.createObjectNode().put("type", "text").put("text", m.getContent()));
            for (ToolCall c : m.getToolCalls()) {
                ObjectNode use = JSON.createObjectNode().put("type", "tool_use").put("id", c.id() == null ? "" : c.id()).put("name", c.name());
                use.set("input", JSON.valueToTree(c.arguments()));
                out.add(use);
            }
            return out;
        }
        out.add(JSON.createObjectNode().put("type", "text").put("text", m.getContent()));
        return out;
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    private Headers headers() {
        return new Headers.Builder()
                .add("x-api-key", config.requireApiKey("Anthropic", baseUrl))
                .add("anthropic-version", API_VERSION)
                .add("content-type", "application/json")
                .build();
    }

    // ── response ────────────────────────────────────────────────────────────────────────────

    LLMResponse parse(JsonNode root, String model) {
        if ("error".equals(root.path("type").asText())) throw streamError(root.path("error"));
        String stop = root.path("stop_reason").asText(null);
        if ("refusal".equals(stop)) throw refusal(root.path("stop_details"));

        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : root.path("content")) {
            String type = block.path("type").asText();
            if ("text".equals(type)) text.append(block.path("text").asText(""));
            else if ("tool_use".equals(type)) {
                calls.add(new ToolCall(block.path("id").asText(null), block.path("name").asText(),
                        JSON.convertValue(block.path("input"), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {})));
            }
            // thinking and redacted_thinking blocks are the model's reasoning, not its answer
        }
        JsonNode u = root.path("usage");
        int input = inputTokens(u);
        int output = u.path("output_tokens").asInt(0);
        java.util.Map<String, Object> raw = java.util.Map.of();
        if (!calls.isEmpty()) {
            // thinking blocks must come back with the tool results, so keep what Claude sent, untouched
            raw = java.util.Map.of(RAW_CONTENT, JSON.convertValue(root.path("content"), new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, Object>>>() {}));
        }
        return LLMResponse.builder()
                .content(text.toString())
                .model(root.path("model").asText(model))
                .tokenUsage(input, output, input + output)
                .toolCalls(calls)
                .providerData(raw)
                .finishReason(calls.isEmpty() ? stop : "tool_use")
                .addMetadata(Providers.FINISH_REASON_RAW, stop)
                .addMetadata("id", root.path("id").asText(null))
                .build();
    }

    /** Input tokens including those written to or read from the prompt cache. */
    private static int inputTokens(JsonNode usage) {
        return usage.path("input_tokens").asInt(0)
                + usage.path("cache_creation_input_tokens").asInt(0)
                + usage.path("cache_read_input_tokens").asInt(0);
    }

    private ContentBlockedException refusal(JsonNode details) {
        String category = details == null || details.isMissingNode() || details.path("category").isNull()
                ? "unspecified" : details.path("category").asText("unspecified");
        String explanation = details == null ? "" : details.path("explanation").asText("");
        return new ContentBlockedException(getProviderName(),
                "refused (" + category + ")" + (explanation.isEmpty() ? "" : ": " + explanation));
    }

    /** An error reported inside a 200 (a stream's {@code error} event). */
    private RuntimeException streamError(JsonNode error) {
        String type = error.path("type").asText("error");
        String message = type + ": " + error.path("message").asText("");
        return switch (type) {
            case "authentication_error", "permission_error" -> new AuthenticationException(message);
            case "invalid_request_error", "not_found_error", "request_too_large" -> new InvalidRequestException(message);
            case "rate_limit_error" -> new RateLimitException(message);
            case "overloaded_error", "api_error" -> new ServiceUnavailableException(getProviderName(), message);
            default -> new ProviderException(getProviderName(), message);
        };
    }
}
