package io.github.llm4j.provider.perplexity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.provider.ClassificationProvider;
import io.github.llm4j.provider.DecisionWire;
import io.github.llm4j.provider.Providers;
import java.util.Objects;
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ClassificationProvider} for Perplexity's Decisions API ({@code POST /v1/decisions}): a
 * decision model that answers typed questions (a choice, a yes/no, a score) directly, with
 * probabilities, in one call, and writes no text. It does not chat, so it is not an {@code
 * LLMProvider}. Use it through {@link io.github.llm4j.classify.Classifier#using}, which also checks
 * every result against the request.
 *
 * <pre>{@code
 * LLMConfig cfg = LLMConfig.builder().apiKey(System.getenv("PERPLEXITY_API_KEY")).defaultModel("pplx-decider-flash-latest").build();
 * Classifier decider = Classifier.using(new PerplexityDecisionsProvider(cfg));
 * }</pre>
 *
 * <p>The wire format is the one Jev uses, kept in {@link DecisionWire}; this class holds only what
 * is Perplexity's: the endpoint, the models ({@code pplx-decider-latest}, 27B, and {@code
 * pplx-decider-flash-latest}, 4B, which is built for one question per request: with several, each
 * is answered alone and the state is billed again for each), bearer authentication only ({@code
 * x-api-key} is not read) and the error body. Input tokens are billed (US$0.02 or 0.01 per million
 * at the time of writing); output tokens are free. Requests go through {@link HttpClientWrapper},
 * so retries (429 and 5xx), timeouts and call telemetry behave as for every other provider. The key
 * is read for each request, sent only to the configured endpoint, and never appears in an exception
 * message, a log line or an event. Its answers are {@link
 * io.github.llm4j.classify.Answer#calibrated() calibrated}.
 */
public class PerplexityDecisionsProvider implements ClassificationProvider {

    private static final Logger logger = LoggerFactory.getLogger(PerplexityDecisionsProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_BASE_URL = "https://api.perplexity.ai";

    /** The 27B model, as the vendor's alias for its newest. */
    public static final String DEFAULT_MODEL = "pplx-decider-latest";

    /** The faster 4B model. */
    public static final String FLASH_MODEL = "pplx-decider-flash-latest";

    private final LLMConfig config;
    private final HttpClientWrapper httpClient;
    private final String baseUrl;
    private final String model;

    public PerplexityDecisionsProvider(LLMConfig config) {
        this(
                config,
                new HttpClientWrapper(Objects.requireNonNull(config, "config cannot be null")));
    }

    PerplexityDecisionsProvider(LLMConfig config, HttpClientWrapper httpClient) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.httpClient = httpClient;
        this.baseUrl =
                (config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URL)
                        .replaceAll("/+$", "");
        this.model = config.getDefaultModel() != null ? config.getDefaultModel() : DEFAULT_MODEL;
        validate();
    }

    @Override
    public ClassifyResult classify(ClassifyRequest request) {
        String body = DecisionWire.requestBody(request, model, DecisionWire.PERPLEXITY);
        long start = System.nanoTime();
        String reply;
        try {
            logger.debug(
                    "Calling Perplexity Decisions with {} question(s)", request.questions().size());
            reply = httpClient.post(baseUrl + "/v1/decisions", body, buildHeaders());
        } catch (InvalidRequestException e) {
            if (Integer.valueOf(400).equals(e.getStatusCode())) {
                // keep the reason the API gives and nothing else: the body is not echoed
                throw new InvalidRequestException(
                        "Perplexity Decisions rejected the request: " + reason(e.getResponseBody()),
                        400,
                        "");
            }
            throw e;
        } catch (LLMException e) {
            throw e;
        } catch (RuntimeException e) {
            throw Providers.typed(getProviderName(), "Classification failed", e);
        }
        return DecisionWire.parse(
                reply, request, (System.nanoTime() - start) / 1_000_000, DecisionWire.PERPLEXITY);
    }

    /** {@code error.message} of a 400 body, shortened; a generic line when there is none. */
    static String reason(String body) {
        try {
            String message = JSON.readTree(body).path("error").path("message").asText("");
            if (message.isBlank()) return "the request was invalid";
            return message.length() > 300 ? message.substring(0, 300) + "…" : message;
        } catch (Exception e) {
            return "the request was invalid";
        }
    }

    @Override
    public String getProviderName() {
        return "perplexity_decisions";
    }

    @Override
    public void validate() {
        if (!config.hasApiKey()) {
            throw new AuthenticationException(
                    config.missingApiKeyMessage(
                            "Perplexity API key is required (PERPLEXITY_API_KEY)"));
        }
    }

    private Headers buildHeaders() {
        return new Headers.Builder()
                .add("Content-Type", "application/json")
                .add("Authorization", "Bearer " + config.requireApiKey("Perplexity", baseUrl))
                .build();
    }
}
