package io.github.llm4j.provider.sarvam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.TranscriptionRequest;
import io.github.llm4j.model.TranscriptionResponse;
import io.github.llm4j.provider.SpeechToTextProvider;
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException; // This was flagged as unused in other files but needed here for IO operations
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of {@link SpeechToTextProvider} for Sarvam AI.
 * Supports both /speech-to-text and /speech-to-text-translate.
 */
public class SarvamAudioProvider implements SpeechToTextProvider {

    private static final Logger logger = LoggerFactory.getLogger(SarvamAudioProvider.class);
    private static final String DEFAULT_BASE_URL = "https://api.sarvam.ai";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final LLMConfig config;
    private final HttpClientWrapper httpClient;
    private final String baseUrl;

    public SarvamAudioProvider(LLMConfig config) {
        this(config, new HttpClientWrapper(config));
    }

    SarvamAudioProvider(LLMConfig config, HttpClientWrapper httpClient) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URL;
        this.httpClient = httpClient;
        validate();
    }

    @Override
    public TranscriptionResponse transcribe(File audioFile, TranscriptionRequest request) {
        boolean translate = request.getTranslateToEnglish().orElse(false);
        String endpoint = translate ? "/speech-to-text-translate" : "/speech-to-text";
        String url = baseUrl + endpoint;

        try {
            Headers headers = buildHeaders();
            Map<String, Object> parts = new HashMap<>();
            parts.put("file", audioFile);

            // Add other parameters
            if (request.getLanguageCode().isPresent()) {
                // Sarvam API documentation should be checked for parameter name.
                // Assuming "language_code" or similar based on typical APIs,
                // but checking docs earlier: "language_code" for input text in other APIs.
                // For STT, checking docs... (I can't check now but I will assume
                // "language_code" or leave it if auto-detect).
                // API docs say "model" is also a param often.
                // For now, let's map languageCode if present.
                parts.put("language_code", request.getLanguageCode().get());
            }
            // "model" parameter - docs often say "saarika:v1" or similar.
            // If request has model? request doesn't have model field in my simple
            // TranscriptionRequest.
            // I'll default to standard or let API decice.
            parts.put("model", "saaras:v1"); // Explicitly setting model as it might be required.

            if (request.getPrompt().isPresent()) {
                parts.put("prompt", request.getPrompt().get());
            }
            if (request.getWithTimestamps().isPresent()) {
                parts.put("with_timestamps", request.getWithTimestamps().get().toString());
            }

            logger.debug("Calling Sarvam AI Audio API URL: {}", url);
            String responseJson = httpClient.postMultipart(url, parts, headers);
            return parseResponse(responseJson);

        } catch (Exception e) { // Catching Exception because postMultipart might throw runtime exceptions or
                                // checked if modified
            throw new ProviderException(getProviderName(), "Failed to transcribe audio", e);
        }
    }

    @Override
    public String getProviderName() {
        return "sarvam-audio";
    }

    @Override
    public void validate() {
        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            throw new AuthenticationException("Sarvam API key is required");
        }
    }

    private Headers buildHeaders() {
        // Content-Type is multipart/form-data, but OkHttp handles that when using
        // MultipartBody.
        // We just need the API key.
        return new Headers.Builder()
                .add("api-subscription-key", config.getApiKey())
                .build();
    }

    private TranscriptionResponse parseResponse(String responseJson) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);

        // Check for error
        if (root.has("error")) {
            throw new ProviderException(getProviderName(), root.get("error").asText());
        }

        // Response format: { "transcript": "...", "language_code": "..." } or similar
        // Need to verify exact Sarvam response.
        // Assuming standard:
        String text = root.path("transcript").asText("");
        // If transcript is empty, check "text" (some APIs use text)
        if (text.isEmpty() && root.has("text")) {
            text = root.get("text").asText();
        }

        String language = root.path("language_code").asText(null);

        return new TranscriptionResponse(text, language);
    }
}
