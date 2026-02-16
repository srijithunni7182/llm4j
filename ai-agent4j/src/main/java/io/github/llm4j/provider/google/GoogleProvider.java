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
import io.github.llm4j.provider.DescribableProvider;
import java.io.IOException;
import java.util.Objects;
import java.util.stream.Stream;
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GoogleProvider implements DescribableProvider {

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
            String requestJson = buildRequestJson(request);
            Headers headers = buildHeaders();

            logger.debug("Calling Google API URL: {}", url);
            String responseJson = httpClient.post(url, requestJson, headers);
            return parseResponse(responseJson, model);
        } catch (ProviderException e) {
            throw e;
        } catch (IOException | LLMException e) {
            throw new ProviderException(getProviderName(), "Failed to process request", e);
        }
    }

    /**
     * Note: This method is not yet implemented for the Google provider. It will throw an {@link
     * UnsupportedOperationException} if called.
     *
     * @param request The LLMRequest object.
     * @return A stream of LLMResponse objects.
     */
    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        logger.warn("chatStream is not yet implemented for the Google provider.");
        throw new UnsupportedOperationException(
                "Streaming is not yet implemented for Google provider");
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
        } catch (IOException | LLMException e) {
            logger.error("Failed to list models from Google API", e);
            throw new ProviderException(getProviderName(), "Failed to list models", e);
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
        } catch (IOException | LLMException e) {
            logger.error("Failed to get available models from Google API", e);
            throw new ProviderException(getProviderName(), "Failed to get available models", e);
        }
    }

    private String buildRequestJson(LLMRequest request) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contentsArray = root.putArray("contents");

        String systemMessage =
                request.getMessages().stream()
                        .filter(m -> m.getRole() == Message.Role.SYSTEM)
                        .map(Message::getContent)
                        .findFirst()
                        .orElse(null);

        boolean firstUserMessage = true;
        for (Message message : request.getMessages()) {
            if (message.getRole() == Message.Role.SYSTEM) {
                continue;
            }

            ObjectNode contentNode = contentsArray.addObject();
            String role = message.getRole() == Message.Role.ASSISTANT ? "model" : "user";
            contentNode.put("role", role);

            ArrayNode partsArray = contentNode.putArray("parts");
            ObjectNode partNode = partsArray.addObject();

            String content = message.getContent();
            if (role.equals("user") && firstUserMessage && systemMessage != null) {
                content = systemMessage + "\n\n" + content;
                firstUserMessage = false;
            }
            partNode.put("text", content);
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

        return objectMapper.writeValueAsString(root);
    }

    private Headers buildHeaders() {
        return new Headers.Builder()
                .add("Content-Type", "application/json")
                .add("x-goog-api-key", config.getApiKey())
                .build();
    }

    private LLMResponse parseResponse(String responseJson, String model) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);

        if (root.has("error")) {
            JsonNode error = root.get("error");
            String message = error.path("message").asText("Unknown error");
            int code = error.path("code").asInt(500);
            if (code == 401 || code == 403) throw new AuthenticationException(message);
            if (code == 400) throw new InvalidRequestException(message);
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

        if ("SAFETY".equals(finishReason)) {
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
                        .build();
            }
            throw new ProviderException(
                    getProviderName(),
                    "No parts in response content: " + candidate.path("content"));
        }

        String textContent = parts.get(0).path("text").asText();

        LLMResponse.TokenUsage tokenUsage = null;
        JsonNode usage = root.path("usageMetadata");
        if (!usage.isMissingNode()) {
            tokenUsage =
                    new LLMResponse.TokenUsage(
                            usage.path("promptTokenCount").asInt(0),
                            usage.path("candidatesTokenCount").asInt(0),
                            usage.path("totalTokenCount").asInt(0));
        }

        return LLMResponse.builder()
                .content(textContent)
                .model(model)
                .tokenUsage(tokenUsage)
                .finishReason(finishReason)
                .build();
    }
}
