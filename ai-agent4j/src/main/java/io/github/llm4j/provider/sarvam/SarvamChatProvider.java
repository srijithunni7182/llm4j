package io.github.llm4j.provider.sarvam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.provider.LLMProvider;
import java.io.IOException;
import java.util.Objects;
import java.util.stream.Stream;
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implementation of {@link LLMProvider} for Sarvam AI Chat Completions. */
public class SarvamChatProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(SarvamChatProvider.class);
    private static final String DEFAULT_BASE_URL = "https://api.sarvam.ai";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final LLMConfig config;
    private final HttpClientWrapper httpClient;
    private final String baseUrl;

    public SarvamChatProvider(LLMConfig config) {
        this(config, new HttpClientWrapper(config));
    }

    // Constructor for testing
    SarvamChatProvider(LLMConfig config, HttpClientWrapper httpClient) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URL;
        this.httpClient = httpClient;
        validate();
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        try {
            String endpoint = "/v1/chat/completions";
            String url = baseUrl + endpoint;
            String requestJson = buildRequestJson(request);
            Headers headers = buildHeaders();

            logger.debug("Calling Sarvam AI Chat API URL: {}", url);
            String responseJson = httpClient.post(url, requestJson, headers);
            return parseResponse(responseJson, request.getModel());
        } catch (IOException | LLMException e) {
            throw new ProviderException(getProviderName(), "Failed to process chat request", e);
        }
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        throw new UnsupportedOperationException(
                "Streaming is not yet implemented for Sarvam provider");
    }

    @Override
    public String getProviderName() {
        return "sarvam-chat";
    }

    @Override
    public void validate() {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            throw new AuthenticationException("Sarvam API key is required");
        }
    }

    private String buildRequestJson(LLMRequest request) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();

        // Model (default to sarvam-2b-v0.5 or what user specifies, user doc mentioned
        // sarvam-m?)
        // Docs example said "sarvam-m".
        String model = request.getModel() != null ? request.getModel() : "sarvam-m";
        root.put("model", model);

        ArrayNode messagesArray = root.putArray("messages");
        for (Message message : request.getMessages()) {
            ObjectNode messageNode = messagesArray.addObject();
            messageNode.put("role", message.getRole().toString().toLowerCase());
            messageNode.put("content", message.getContent());
        }

        if (request.getTemperature() != null) root.put("temperature", request.getTemperature());
        if (request.getMaxTokens() != null) root.put("max_tokens", request.getMaxTokens());
        if (request.getTopP() != null) root.put("top_p", request.getTopP());
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            ArrayNode stopArray = root.putArray("stop");
            request.getStopSequences().forEach(stopArray::add);
        }

        return objectMapper.writeValueAsString(root);
    }

    private Headers buildHeaders() {
        return new Headers.Builder()
                .add("Content-Type", "application/json")
                .add("api-subscription-key", config.getApiKey())
                .build();
    }

    private LLMResponse parseResponse(String responseJson, String requestedModel)
            throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);

        if (root.has("error")) {
            throw new ProviderException(getProviderName(), root.get("error").asText());
        }

        JsonNode choices = root.path("choices");
        if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
            throw new ProviderException(
                    getProviderName(), "No choices in response: " + responseJson);
        }

        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").asText();
        String finishReason = choice.path("finish_reason").asText();

        LLMResponse.TokenUsage tokenUsage = null;
        if (root.has("usage")) {
            JsonNode usage = root.get("usage");
            tokenUsage =
                    new LLMResponse.TokenUsage(
                            usage.path("prompt_tokens").asInt(0),
                            usage.path("completion_tokens").asInt(0),
                            usage.path("total_tokens").asInt(0));
        }

        return LLMResponse.builder()
                .content(content)
                .model(requestedModel) // Or root.path("model").asText()
                .tokenUsage(tokenUsage)
                .finishReason(finishReason)
                .build();
    }
}
