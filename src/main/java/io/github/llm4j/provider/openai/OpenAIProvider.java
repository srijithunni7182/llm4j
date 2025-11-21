package io.github.llm4j.provider.openai;

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
 * OpenAI API provider implementation.
 * Supports ChatGPT models (GPT-3.5, GPT-4, etc.)
 */
public class OpenAIProvider implements LLMProvider {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String CHAT_ENDPOINT = "/chat/completions";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final LLMConfig config;
    private final HttpClientWrapper httpClient;
    private final String baseUrl;

    public OpenAIProvider(LLMConfig config) {
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

            String responseJson = httpClient.post(baseUrl + CHAT_ENDPOINT, requestJson, headers);
            return parseResponse(responseJson);
        } catch (IOException e) {
            throw new ProviderException(getProviderName(), "Failed to process request", e);
        }
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        // Streaming implementation would use Server-Sent Events (SSE)
        // For simplicity in this initial version, we'll throw an
        // UnsupportedOperationException
        // A full implementation would parse SSE events from the response
        throw new UnsupportedOperationException("Streaming is not yet implemented for OpenAI provider");
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public void validate() {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            throw new AuthenticationException("OpenAI API key is required");
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

        // Messages
        ArrayNode messagesArray = root.putArray("messages");
        for (Message message : request.getMessages()) {
            ObjectNode messageNode = messagesArray.addObject();
            messageNode.put("role", message.getRole().getValue());
            messageNode.put("content", message.getContent());
            if (message.getName() != null) {
                messageNode.put("name", message.getName());
            }
        }

        // Optional parameters
        if (request.getTemperature() != null) {
            root.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            root.put("max_tokens", request.getMaxTokens());
        }
        if (request.getTopP() != null) {
            root.put("top_p", request.getTopP());
        }
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            ArrayNode stopArray = root.putArray("stop");
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
                .add("Authorization", "Bearer " + config.getApiKey())
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
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new ProviderException(getProviderName(), "No choices in response");
        }

        JsonNode firstChoice = choices.get(0);
        JsonNode message = firstChoice.get("message");
        String content = message.get("content").asText();
        String finishReason = firstChoice.has("finish_reason") ? firstChoice.get("finish_reason").asText() : null;

        // Extract usage
        LLMResponse.TokenUsage tokenUsage = null;
        if (root.has("usage")) {
            JsonNode usage = root.get("usage");
            tokenUsage = new LLMResponse.TokenUsage(
                    usage.get("prompt_tokens").asInt(),
                    usage.get("completion_tokens").asInt(),
                    usage.get("total_tokens").asInt());
        }

        String model = root.has("model") ? root.get("model").asText() : null;

        return LLMResponse.builder()
                .content(content)
                .model(model)
                .tokenUsage(tokenUsage)
                .finishReason(finishReason)
                .build();
    }
}
