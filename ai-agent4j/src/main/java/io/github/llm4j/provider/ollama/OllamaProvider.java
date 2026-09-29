package io.github.llm4j.provider.ollama;

import io.github.llm4j.provider.Providers;

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
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.stream.Stream;

public class OllamaProvider implements DescribableProvider {
    private static final Logger logger = LoggerFactory.getLogger(OllamaProvider.class);
    private static final String DEFAULT_BASE_URL = "http://localhost:11434/api";

    private final LLMConfig config;
    private final HttpClientWrapper httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public OllamaProvider(LLMConfig config) {
        this(config, new HttpClientWrapper(config != null ? config : LLMConfig.builder().build()), new ObjectMapper());
    }

    public OllamaProvider(LLMConfig config, HttpClientWrapper httpClient, ObjectMapper objectMapper) {
        this.config = config != null ? config : LLMConfig.builder().build();
        this.baseUrl = this.config.getBaseUrl() != null ? this.config.getBaseUrl() : DEFAULT_BASE_URL;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        validate();
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        try {
            String model = request.getModel() != null ? request.getModel() : config.getDefaultModel();
            if (model == null) {
                model = getFirstAvailableModel();
                if (model == null) {
                    throw new InvalidRequestException("Model must be specified in request or config, and no models found on server");
                }
            }

            String url = baseUrl + "/chat";
            String requestJson = buildRequestJson(request, model);
            Headers headers = buildHeaders();

            logger.debug("Calling Ollama API URL: {}", url);
            String responseJson = httpClient.post(url, requestJson, headers);
            return parseResponse(responseJson, model);
        } catch (ProviderException e) {
            throw e;
        } catch (io.github.llm4j.exception.RateLimitException e) {
            throw e;
        } catch (IOException | LLMException e) {
            throw Providers.typed(getProviderName(), "Failed to process request", e);
        }
    }

    /**
     * Streams with {@code "stream": true}: Ollama answers with one JSON object per line; each line's
     * {@code message.content} is a chunk, and the line with {@code done: true} carries the finish reason
     * and token counts for the final chunk.
     */
    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        String model = request.getModel() != null ? request.getModel() : config.getDefaultModel();
        if (model == null) model = getFirstAvailableModel();
        if (model == null) {
            throw new InvalidRequestException("Model must be specified in request or config, and no models found on server");
        }
        String answering = model;
        io.github.llm4j.http.StreamingBody body;
        try {
            body = httpClient.stream(baseUrl + "/chat", buildRequestJson(request, model, true), buildHeaders());
        } catch (IOException | LLMException e) {
            throw Providers.typed(getProviderName(), "Failed to start streaming", e);
        }
        return body.lines().map(line -> {
            JsonNode root;
            try {
                root = objectMapper.readTree(line);
            } catch (IOException e) {
                throw new ProviderException(getProviderName(), "Unreadable stream line: " + line, e);
            }
            if (root.has("error")) {
                throw new ProviderException(getProviderName(), root.path("error").asText("Unknown error"), 500);
            }
            if (root.path("done").asBoolean(false)) {
                String reason = root.has("done_reason") ? root.path("done_reason").asText() : "stop";
                return Providers.finalChunk(reason,
                        root.has("prompt_eval_count") ? root.path("prompt_eval_count").asInt() : null,
                        root.has("eval_count") ? root.path("eval_count").asInt() : null, answering);
            }
            return Providers.textChunk(root.path("message").path("content").asText(""), answering);
        }).filter(chunk -> !chunk.getContent().isEmpty() || chunk.getFinishReason() != LLMResponse.FinishReason.UNKNOWN
                || chunk.getTokenUsage() != null).onClose(body::close);
    }

    @Override
    public String getProviderName() {
        return "ollama";
    }

    @Override
    public void validate() {
        // Ollama usually does not require authentication out of the box
    }

    @Override
    public String[] listModels() {
        try {
            String url = baseUrl + "/tags";
            String responseJson = httpClient.get(url, buildHeaders());
            JsonNode root = objectMapper.readTree(responseJson);

            if (root.has("models")) {
                ArrayNode modelsArray = (ArrayNode) root.get("models");
                String[] models = new String[modelsArray.size()];
                for (int i = 0; i < modelsArray.size(); i++) {
                    models[i] = modelsArray.get(i).path("name").asText();
                }
                return models;
            }
            return new String[0];
        } catch (ProviderException e) {
            throw e;
        } catch (io.github.llm4j.exception.RateLimitException e) {
            throw e;
        } catch (IOException | LLMException e) {
            logger.error("Failed to list models from Ollama API", e);
            throw Providers.typed(getProviderName(), "Failed to list models", e);
        }
    }

    @Override
    public String getFirstAvailableModel() {
        String[] models = listModels();
        if (models != null && models.length > 0) {
            for (String model : models) {
                if (model.toLowerCase().contains("gemma")) {
                    return model;
                }
            }
            return models[0];
        }
        return null;
    }

    private String buildRequestJson(LLMRequest request, String model) throws IOException {
        return buildRequestJson(request, model, false);
    }

    private String buildRequestJson(LLMRequest request, String model, boolean stream) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("stream", stream);

        ArrayNode messagesArray = root.putArray("messages");

        for (Message message : request.getMessages()) {
            ObjectNode messageNode = messagesArray.addObject();
            String role = message.getRole().name().toLowerCase();
            messageNode.put("role", role);
            messageNode.put("content", message.getContent());
        }

        ObjectNode optionsNode = root.putObject("options");
        if (request.getTemperature() != null) optionsNode.put("temperature", request.getTemperature());
        if (request.getMaxTokens() != null) optionsNode.put("num_predict", request.getMaxTokens());
        if (request.getTopP() != null) optionsNode.put("top_p", request.getTopP());
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            ArrayNode stopArray = optionsNode.putArray("stop");
            request.getStopSequences().forEach(stopArray::add);
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

        if (root.has("error")) {
            String message = root.path("error").asText("Unknown error");
            throw new ProviderException(getProviderName(), message, 500);
        }

        JsonNode messageNode = root.path("message");
        if (messageNode.isMissingNode()) {
            throw new ProviderException(getProviderName(), "No message in response: " + responseJson);
        }

        String textContent = messageNode.path("content").asText();

        LLMResponse.TokenUsage tokenUsage = null;
        if (root.has("prompt_eval_count") && root.has("eval_count")) {
            int promptTokens = root.path("prompt_eval_count").asInt(0);
            int evalTokens = root.path("eval_count").asInt(0);
            tokenUsage = new LLMResponse.TokenUsage(promptTokens, evalTokens, promptTokens + evalTokens);
        }

        String finishReason = root.has("done_reason") ? root.path("done_reason").asText() : (root.path("done").asBoolean() ? "stop" : null);

        return LLMResponse.builder()
                .content(textContent)
                .model(model)
                .tokenUsage(tokenUsage)
                .finishReason(finishReason)
                .addMetadata(Providers.FINISH_REASON_RAW, finishReason)
                .build();
    }
}
