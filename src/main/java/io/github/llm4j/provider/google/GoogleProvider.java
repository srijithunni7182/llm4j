package io.github.llm4j.provider.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.ProviderException;
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
 * Google Gemini API provider implementation.
 */
public class GoogleProvider implements LLMProvider {

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final LLMConfig config;
    private final HttpClientWrapper httpClient;
    private final String baseUrl;

    public GoogleProvider(LLMConfig config) {
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
            String model = request.getModel() != null ? request.getModel() : config.getDefaultModel();
            if (model == null) {
                throw new InvalidRequestException("Model must be specified either in request or config");
            }

            String endpoint = String.format("/models/%s:generateContent?key=%s", model, config.getApiKey());
            String requestJson = buildRequestJson(request);
            Headers headers = buildHeaders();

            String responseJson = httpClient.post(baseUrl + endpoint, requestJson, headers);
            return parseResponse(responseJson, model);
        } catch (IOException e) {
            throw new ProviderException(getProviderName(), "Failed to process request", e);
        }
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        throw new UnsupportedOperationException("Streaming is not yet implemented for Google provider");
    }

    @Override
    public String getProviderName() {
        return "google";
    }

    @Override
    public void validate() {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            throw new AuthenticationException("Google API key is required");
        }
    }

    private String buildRequestJson(LLMRequest request) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();

        // Google Gemini uses "contents" array
        ArrayNode contentsArray = root.putArray("contents");

        for (Message message : request.getMessages()) {
            ObjectNode contentNode = contentsArray.addObject();

            // Google uses "user" and "model" roles instead of "assistant"
            String role = message.getRole() == Message.Role.ASSISTANT ? "model" : "user";
            contentNode.put("role", role);

            ArrayNode partsArray = contentNode.putArray("parts");
            ObjectNode partNode = partsArray.addObject();
            partNode.put("text", message.getContent());
        }

        // Generation config
        if (request.getTemperature() != null || request.getMaxTokens() != null ||
                request.getTopP() != null || request.getStopSequences() != null) {

            ObjectNode generationConfig = root.putObject("generationConfig");

            if (request.getTemperature() != null) {
                generationConfig.put("temperature", request.getTemperature());
            }
            if (request.getMaxTokens() != null) {
                generationConfig.put("maxOutputTokens", request.getMaxTokens());
            }
            if (request.getTopP() != null) {
                generationConfig.put("topP", request.getTopP());
            }
            if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
                ArrayNode stopArray = generationConfig.putArray("stopSequences");
                request.getStopSequences().forEach(stopArray::add);
            }
        }

        return objectMapper.writeValueAsString(root);
    }

    private Headers buildHeaders() {
        return new Headers.Builder()
                .add("Content-Type", "application/json")
                .build();
    }

    private LLMResponse parseResponse(String responseJson, String model) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);

        // Check for errors
        if (root.has("error")) {
            JsonNode error = root.get("error");
            String errorMessage = error.has("message") ? error.get("message").asText() : "Unknown error";
            int statusCode = error.has("code") ? error.get("code").asInt() : 500;

            if (statusCode == 401 || statusCode == 403) {
                throw new AuthenticationException(errorMessage);
            } else if (statusCode == 400) {
                throw new InvalidRequestException(errorMessage);
            } else {
                throw new ProviderException(getProviderName(), errorMessage, statusCode);
            }
        }

        // Extract response data
        JsonNode candidates = root.get("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            throw new ProviderException(getProviderName(), "No candidates in response");
        }

        JsonNode firstCandidate = candidates.get(0);
        JsonNode content = firstCandidate.get("content");
        JsonNode parts = content.get("parts");

        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            throw new ProviderException(getProviderName(), "No parts in response");
        }

        String textContent = parts.get(0).get("text").asText();
        String finishReason = firstCandidate.has("finishReason") ? firstCandidate.get("finishReason").asText() : null;

        // Extract usage metadata (if available)
        LLMResponse.TokenUsage tokenUsage = null;
        if (root.has("usageMetadata")) {
            JsonNode usage = root.get("usageMetadata");
            tokenUsage = new LLMResponse.TokenUsage(
                    usage.has("promptTokenCount") ? usage.get("promptTokenCount").asInt() : 0,
                    usage.has("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : 0,
                    usage.has("totalTokenCount") ? usage.get("totalTokenCount").asInt() : 0);
        }

        return LLMResponse.builder()
                .content(textContent)
                .model(model)
                .tokenUsage(tokenUsage)
                .finishReason(finishReason)
                .build();
    }
}
