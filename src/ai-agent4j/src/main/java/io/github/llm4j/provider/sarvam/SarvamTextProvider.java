package io.github.llm4j.provider.sarvam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.*;
import io.github.llm4j.provider.LanguageDetectionProvider;
import io.github.llm4j.provider.TranslationProvider;
import io.github.llm4j.provider.TransliterationProvider;
import java.util.Objects;
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link TranslationProvider}, {@link TransliterationProvider}, and {@link
 * LanguageDetectionProvider} for Sarvam AI.
 */
public class SarvamTextProvider
        implements TranslationProvider, TransliterationProvider, LanguageDetectionProvider {

    private static final Logger logger = LoggerFactory.getLogger(SarvamTextProvider.class);
    private static final String DEFAULT_BASE_URL = "https://api.sarvam.ai";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final LLMConfig config;
    private final HttpClientWrapper httpClient;
    private final String baseUrl;

    public SarvamTextProvider(LLMConfig config) {
        this(config, new HttpClientWrapper(config));
    }

    SarvamTextProvider(LLMConfig config, HttpClientWrapper httpClient) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URL;
        this.httpClient = httpClient;
        validate();
    }

    @Override
    public TranslationResponse translate(TranslationRequest request) {
        String url = baseUrl + "/translate";
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("input", request.getText());
            root.put(
                    "source_language_code",
                    request.getSourceLanguageCode().orElse("en-IN")); // Default is often
            // required or auto. Docs
            // check?
            // Docs imply source_language_code and target_language_code are required.
            root.put("target_language_code", request.getTargetLanguageCode());
            root.put("speaker_gender", request.getSpeakerGender().orElse("Male"));
            root.put("mode", request.getMode().orElse("formal"));
            // Sarvam Translate typically implies enabling processing? Or just standard.

            logger.debug("Calling Sarvam AI Translate API");
            String responseJson =
                    httpClient.post(url, objectMapper.writeValueAsString(root), buildHeaders());

            JsonNode responseRoot = objectMapper.readTree(responseJson);
            if (responseRoot.has("error")) {
                throw new ProviderException(getProviderName(), responseRoot.get("error").asText());
            }

            String translatedText = responseRoot.path("translated_text").asText();
            // Fallback
            if (translatedText.isEmpty() && responseRoot.has("text")) {
                translatedText = responseRoot.get("text").asText();
            }

            return new TranslationResponse(
                    translatedText, request.getSourceLanguageCode().orElse(null));

        } catch (io.github.llm4j.exception.RateLimitException e) {
            throw e;
        } catch (Exception e) {
            throw io.github.llm4j.provider.Providers.typed(getProviderName(), "Translation failed", e);
        }
    }

    @Override
    public TransliterationResponse transliterate(TransliterationRequest request) {
        String url = baseUrl + "/transliterate";
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("input", request.getText());
            root.put("source_language_code", request.getSourceLanguageCode().orElse("en-IN"));
            root.put("target_language_code", request.getTargetLanguageCode());
            // Optional params
            if (request.getOutputScript().isPresent()) {
                // Sarvam parameter might be output_script? Or maybe just inferred from target
                // code.
                // Assuming standard params if documented.
            }

            logger.debug("Calling Sarvam AI Transliterate API");
            String responseJson =
                    httpClient.post(url, objectMapper.writeValueAsString(root), buildHeaders());

            JsonNode responseRoot = objectMapper.readTree(responseJson);
            if (responseRoot.has("error")) {
                throw new ProviderException(getProviderName(), responseRoot.get("error").asText());
            }

            String transliterated = responseRoot.path("transliterated_text").asText();
            if (transliterated.isEmpty() && responseRoot.has("text")) {
                transliterated = responseRoot.get("text").asText();
            }

            return new TransliterationResponse(transliterated);

        } catch (io.github.llm4j.exception.RateLimitException e) {
            throw e;
        } catch (Exception e) {
            throw io.github.llm4j.provider.Providers.typed(getProviderName(), "Transliteration failed", e);
        }
    }

    @Override
    public LanguageDetectionResponse detectLanguage(String text) {
        // Sarvam's language identification endpoint
        String url = baseUrl + "/text-lid";

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("input", text);

            logger.debug("Calling Sarvam AI Language Detection API");
            String responseJson =
                    httpClient.post(url, objectMapper.writeValueAsString(root), buildHeaders());

            JsonNode responseRoot = objectMapper.readTree(responseJson);
            // {"request_id": …, "language_code": "hi-IN", "script_code": "Deva"}

            if (responseRoot.has("error")) {
                throw new ProviderException(getProviderName(), responseRoot.get("error").asText());
            }

            String languageCode = responseRoot.path("language_code").asText();
            String script =
                    responseRoot.hasNonNull("script_code")
                            ? responseRoot.get("script_code").asText()
                            : null;
            Double confidence =
                    responseRoot.has("confidence")
                            ? responseRoot.get("confidence").asDouble()
                            : null;

            return new LanguageDetectionResponse(languageCode, script, confidence);

        } catch (io.github.llm4j.exception.RateLimitException e) {
            throw e;
        } catch (Exception e) {
            throw io.github.llm4j.provider.Providers.typed(getProviderName(), "Language detection failed", e);
        }
    }

    @Override
    public String getProviderName() {
        return "sarvam-text";
    }

    @Override
    public void validate() {
        if (!config.hasApiKey()) {
            throw new AuthenticationException(config.missingApiKeyMessage("Sarvam API key is required"));
        }
    }

    private Headers buildHeaders() {
        return new Headers.Builder()
                .add("Content-Type", "application/json")
                .add("api-subscription-key", config.requireApiKey("Sarvam", baseUrl))
                .build();
    }
}
