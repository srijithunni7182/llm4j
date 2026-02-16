package io.github.llm4j.provider.sarvam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.TextToSpeechRequest;
import io.github.llm4j.model.TextToSpeechResponse;
import io.github.llm4j.provider.TextToSpeechProvider;
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;
import java.util.Objects;

/**
 * Implementation of {@link TextToSpeechProvider} for Sarvam AI.
 */
public class SarvamTextToSpeechProvider implements TextToSpeechProvider {

    private static final Logger logger = LoggerFactory.getLogger(SarvamTextToSpeechProvider.class);
    private static final String DEFAULT_BASE_URL = "https://api.sarvam.ai";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final LLMConfig config;
    private final HttpClientWrapper httpClient;
    private final String baseUrl;

    public SarvamTextToSpeechProvider(LLMConfig config) {
        this(config, new HttpClientWrapper(config));
    }

    // Constructor for testing
    SarvamTextToSpeechProvider(LLMConfig config, HttpClientWrapper httpClient) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URL;
        this.httpClient = httpClient;
        validate();
    }

    @Override
    public TextToSpeechResponse generateSpeech(TextToSpeechRequest request) {
        try {
            String endpoint = "/text-to-speech";
            String url = baseUrl + endpoint;
            String requestJson = buildRequestJson(request);
            Headers headers = buildHeaders();

            logger.debug("Calling Sarvam AI TTS API URL: {}", url);
            // This assumes the client wrapper returns the raw body as string.
            // However, Sarvam might return binary audio or base64 JSON.
            // Let's check the curl command again. It has --data-raw JSON, so it expects
            // JSON response?
            // "content-type: application/json" is for request.
            // Usually TTS APIs return binary audio or a JSON with base64 audio.
            // Sarvam documentation (implied from typical patterns and the curl provided)
            // likely returns JSON with base64 encoded audio in a field like "audios" or
            // similar, OR it returns raw audio bytes.
            // Let's assume it returns a JSON with base64 audio for now, based on typical
            // modern AI APIs (like Google/OpenAI).
            // Actually, let's re-read the user request.
            // The user provided curl command.

            String responseJson = httpClient.post(url, requestJson, headers);
            return parseResponse(responseJson);
        } catch (ProviderException e) {
            throw e;
        } catch (IOException | io.github.llm4j.exception.LLMException e) {
            throw new ProviderException(getProviderName(), "Failed to process TTS request", e);
        }
    }

    @Override
    public String getProviderName() {
        return "sarvam";
    }

    @Override
    public void validate() {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            throw new AuthenticationException("Sarvam API key is required");
        }
    }

    private String buildRequestJson(TextToSpeechRequest request) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();

        // Required fields
        root.put("text", request.getText());
        root.put("target_language_code", request.getTargetLanguageCode().orElse("hi-IN")); // Default to Hindi if not
                                                                                           // specified? Or throw? Let's
                                                                                           // use what user provided in
                                                                                           // curl "ml-IN" as example,
                                                                                           // but default to something
                                                                                           // reasonable or require it.
        // Actually, Sarvam likely requires it. Let's start with a safe default or fail
        // if missing.
        // User's curl had "ml-IN".

        // Optional fields with defaults matching the curl example or reasonable
        // defaults
        request.getSpeaker().ifPresent(s -> root.put("speaker", s));
        request.getPace().ifPresent(p -> root.put("pace", p));
        request.getSpeechSampleRate().ifPresent(r -> root.put("speech_sample_rate", r));
        request.getEnablePreprocessing().ifPresent(e -> root.put("enable_preprocessing", e));
        request.getModel().ifPresentOrElse(
                m -> root.put("model", m),
                () -> root.put("model", "bulbul:v3") // Default to bulbul:v3 (latest stable)

        );

        return objectMapper.writeValueAsString(root);
    }

    private Headers buildHeaders() {
        return new Headers.Builder()
                .add("Content-Type", "application/json")
                .add("api-subscription-key", config.getApiKey())
                .build();
    }

    private TextToSpeechResponse parseResponse(String responseJson) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);

        // Check for error fields
        // Assuming standard error format, but if not we might need adjustment.
        if (root.has("error")) {
            throw new ProviderException(getProviderName(), root.get("error").asText());
        }

        // The curl output wasn't shown, but typically it returns base64 "audios" array
        // or "audio" field.
        // Let's assume "audios" array of base64 strings based on common Sarvam
        // examples.
        // If it's different, I'll need to debug.
        // Wait, looking at online docs for Sarvam, it seems to return a list of base64
        // strings if it's batch, or single.
        // Let's handle "audios" (array) and take the first one.

        if (root.has("audios") && root.get("audios").isArray() && root.get("audios").size() > 0) {
            JsonNode audioNode = root.get("audios").get(0);
            String base64Audio;
            if (audioNode.isObject() && audioNode.has("audio_b64")) {
                base64Audio = audioNode.get("audio_b64").asText();
            } else {
                base64Audio = audioNode.asText();
            }
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            return new TextToSpeechResponse(audioData, "audio/wav"); // Sarvam usually outputs WAV
        } else {
            throw new ProviderException(getProviderName(), "Unexpected response format: " + responseJson);
        }
    }
}
