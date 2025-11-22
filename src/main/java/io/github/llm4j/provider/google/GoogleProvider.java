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

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1";
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

    /**
     * List available models from Google Gemini API.
     * 
     * @return Array of available model names
     */
    public String[] listModels() {
        try {
            String endpoint = "/models?key=" + config.getApiKey();
            Headers headers = buildHeaders();

            String responseJson = httpClient.get(baseUrl + endpoint, headers);
            JsonNode root = objectMapper.readTree(responseJson);

            if (root.has("models")) {
                JsonNode models = root.get("models");
                return objectMapper.convertValue(models, String[].class);
            }

            return new String[0];
        } catch (Exception e) {
            // If listing fails, return empty array
            return new String[0];
        }
    }

    /**
     * Get the first available Gemini model that supports generateContent.
     * 
     * @return Model name or null if none found
     */
    public String getFirstAvailableModel() {
        try {
            String endpoint = "/models?key=" + config.getApiKey();
            Headers headers = buildHeaders();

            String responseJson = httpClient.get(baseUrl + endpoint, headers);
            JsonNode root = objectMapper.readTree(responseJson);

            if (root.has("models")) {
                JsonNode models = root.get("models");

                for (JsonNode model : models) {
                    String modelName = model.get("name").asText();
                    // Extract just the model ID from "models/gemini-xxx"
                    String modelId = modelName.replace("models/", "");

                    // Check if this model supports generateContent
                    if (model.has("supportedGenerationMethods")) {
                        JsonNode methods = model.get("supportedGenerationMethods");
                        for (JsonNode method : methods) {
                            if (method.asText().equals("generateContent")) {
                                // Return first gemini model that supports generateContent
                                if (modelId.contains("gemini")) {
                                    return modelId;
                                }
                            }
                        }
                    }
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildRequestJson(LLMRequest request) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();

        // Google Gemini v1 API doesn't support systemInstruction field
        // We need to convert system messages to user messages or prepend to first user
        // message
        ArrayNode contentsArray = root.putArray("contents");

        // Find system message if present
        String systemMessage = null;
        for (Message message : request.getMessages()) {
            if (message.getRole() == Message.Role.SYSTEM) {
                systemMessage = message.getContent();
                break;
            }
        }

        boolean firstUserMessage = true;
        for (Message message : request.getMessages()) {
            // Skip system messages - we'll prepend to first user message
            if (message.getRole() == Message.Role.SYSTEM) {
                continue;
            }

            ObjectNode contentNode = contentsArray.addObject();

            // Google uses "user" and "model" roles
            String role = message.getRole() == Message.Role.ASSISTANT ? "model" : "user";
            contentNode.put("role", role);

            ArrayNode partsArray = contentNode.putArray("parts");
            ObjectNode partNode = partsArray.addObject();

            // If this is the first user message and we have a system message, prepend it
            if (role.equals("user") && firstUserMessage && systemMessage != null) {
                partNode.put("text", systemMessage + "\n\n" + message.getContent());
                firstUserMessage = false;
            } else {
                partNode.put("text", message.getContent());
            }
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
            // Check for prompt feedback (safety filters)
            if (root.has("promptFeedback")) {
                JsonNode feedback = root.get("promptFeedback");
                if (feedback.has("blockReason")) {
                    String blockReason = feedback.get("blockReason").asText();
                    throw new ProviderException(getProviderName(),
                            "Content blocked by safety filters: " + blockReason);
                }
            }
            throw new ProviderException(getProviderName(),
                    "No candidates in response. Full response: " + responseJson);
        }

        JsonNode firstCandidate = candidates.get(0);

        // Check if content was blocked by safety filters
        if (firstCandidate.has("finishReason")) {
            String finishReason = firstCandidate.get("finishReason").asText();
            if ("SAFETY".equals(finishReason)) {
                String safetyInfo = "";
                if (firstCandidate.has("safetyRatings")) {
                    safetyInfo = " Safety ratings: " + firstCandidate.get("safetyRatings").toString();
                }
                throw new ProviderException(getProviderName(),
                        "Content blocked by safety filters." + safetyInfo);
            }
        }

        // Extract content
        JsonNode content = firstCandidate.get("content");
        if (content == null) {
            throw new ProviderException(getProviderName(),
                    "No content in candidate. Candidate: " + firstCandidate.toString());
        }

        JsonNode parts = content.get("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            // Gemini 2.5 uses thinking tokens, and if MAX_TOKENS is hit during thinking,
            // there may be no actual content part. Check if this is the case.
            String finishReason = firstCandidate.has("finishReason") ? firstCandidate.get("finishReason").asText()
                    : null;

            if ("MAX_TOKENS".equals(finishReason)) {
                // Model hit token limit before generating output
                // Return a helpful message instead of throwing exception
                return LLMResponse.builder()
                        .content("[Response truncated: model hit token limit before generating output. " +
                                "Please increase maxTokens parameter.]")
                        .model(model)
                        .finishReason(finishReason)
                        .build();
            }

            // For other cases, log and throw exception
            System.err.println("DEBUG: Full response JSON: " + responseJson);
            System.err.println("DEBUG: Candidate: " + firstCandidate.toString());
            System.err.println("DEBUG: Content: " + content.toString());

            throw new ProviderException(getProviderName(),
                    "No parts in response. Content: " + content.toString());
        }

        JsonNode firstPart = parts.get(0);
        if (!firstPart.has("text")) {
            throw new ProviderException(getProviderName(),
                    "No text in first part. Part: " + firstPart.toString());
        }

        String textContent = firstPart.get("text").asText();
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
