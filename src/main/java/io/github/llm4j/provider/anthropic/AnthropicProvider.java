package io.github.llm4j.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.provider.LLMProvider;
import okhttp3.Headers;

import java.io.IOException;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Anthropic API provider implementation.
 * Supports Claude models (Claude 3, etc.)
 */
public class AnthropicProvider implements LLMProvider {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";
    private static final String MESSAGES_ENDPOINT = "/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final LLMConfig config;
    private final HttpClientWrapper httpClient;
    private final String baseUrl;

    public AnthropicProvider(LLMConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URL;
        this.httpClient = new HttpClientWrapper(
                config.getTimeout(),
                config.getConnectTimeout(),
                config.getRetryPolicy(),
                config.isEnableLogging());
        validate();
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        try {
            String requestJson = buildRequestJson(request, false);
            Headers headers = buildHeaders();

            String responseJson = httpClient.post(baseUrl + MESSAGES_ENDPOINT, requestJson, headers);
            return parseResponse(responseJson);
        } catch (IOException e) {
            throw new ProviderException(getProviderName(), "Failed to process request", e);
        }
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        throw new UnsupportedOperationException("Streaming is not yet implemented for Anthropic provider");
    }

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    @Override
    public void validate() {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            throw new AuthenticationException("Anthropic API key is required");
        }
    }

    private String buildRequestJson(LLMRequest request, boolean stream) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();

        // Model
        String model = request.getModel() != null ? request.getModel() : config.getDefaultModel();
        if (model == null) {
            throw new InvalidRequestException("Model must be specified either in request or config");
        }
        root.put("model", model);

        // Anthropic requires system messages to be separate
        String systemMessage = null;
        ArrayNode messagesArray = root.putArray("messages");

        for (Message message : request.getMessages()) {
            if (message.getRole() == Message.Role.SYSTEM) {
                // Anthropic API expects system message separate from messages array
                systemMessage = message.getContent();
            } else {
                ObjectNode messageNode = messagesArray.addObject();
                messageNode.put("role", message.getRole().getValue());
                messageNode.put("content", message.getContent());
            }
        }

        if (systemMessage != null) {
            root.put("system", systemMessage);
        }

        // Max tokens is required for Anthropic
        int maxTokens = request.getMaxTokens() != null ? request.getMaxTokens() : 1024;
        root.put("max_tokens", maxTokens);

        // Optional parameters
        if (request.getTemperature() != null) {
            root.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            root.put("top_p", request.getTopP());
        }
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            ArrayNode stopArray = root.putArray("stop_sequences");
            request.getStopSequences().forEach(stopArray::add);
        }

        // Streaming
        root.put("stream", stream);

        // Additional parameters
        request.getAdditionalParameters().forEach((key, value) -> {
            if (value instanceof String) {
                root.put(key, (String) value);
            } else if (value instanceof Number) {
                root.put(key, ((Number) value).doubleValue());
            } else if (value instanceof Boolean) {
                root.put(key, (Boolean) value);
            }
        });

        return objectMapper.writeValueAsString(root);
    }

    private Headers buildHeaders() {
        return new Headers.Builder()
                .add("x-api-key", config.getApiKey())
                .add("anthropic-version", ANTHROPIC_VERSION)
                .add("Content-Type", "application/json")
                .build();
    }

    private LLMResponse parseResponse(String responseJson) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);

        // Check for errors
        if (root.has("error")) {
            JsonNode error = root.get("error");
            String errorMessage = error.has("message") ? error.get("message").asText() : "Unknown error";
            String errorType = error.has("type") ? error.get("type").asText() : "unknown";

            switch (errorType) {
                case "invalid_request_error":
                    throw new InvalidRequestException(errorMessage);
                case "authentication_error":
                    throw new AuthenticationException(errorMessage);
                case "rate_limit_error":
                    throw new RateLimitException(errorMessage);
                default:
                    throw new ProviderException(getProviderName(), errorMessage);
            }
        }

        // Extract response data
        JsonNode content = root.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw new ProviderException(getProviderName(), "No content in response");
        }

        // Anthropic returns content as an array of content blocks
        String textContent = content.get(0).get("text").asText();
        String finishReason = root.has("stop_reason") ? root.get("stop_reason").asText() : null;

        // Extract usage
        LLMResponse.TokenUsage tokenUsage = null;
        if (root.has("usage")) {
            JsonNode usage = root.get("usage");
            tokenUsage = new LLMResponse.TokenUsage(
                    usage.get("input_tokens").asInt(),
                    usage.get("output_tokens").asInt(),
                    usage.get("input_tokens").asInt() + usage.get("output_tokens").asInt());
        }

        String model = root.has("model") ? root.get("model").asText() : null;

        return LLMResponse.builder()
                .content(textContent)
                .model(model)
                .tokenUsage(tokenUsage)
                .finishReason(finishReason)
                .build();
    }
}
