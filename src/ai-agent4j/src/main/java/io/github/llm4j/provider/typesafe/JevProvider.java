package io.github.llm4j.provider.typesafe;

import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.provider.ClassificationProvider;
import io.github.llm4j.provider.Providers;
import java.util.Objects;
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ClassificationProvider} for TypeSafe AI's Jev, a decision model that answers typed
 * questions (a choice, a yes/no, a score) directly, with probabilities, in one call and without
 * writing text. It does not chat, so it is not an {@code LLMProvider}. Use it through {@link
 * io.github.llm4j.classify.Classifier#using(ClassificationProvider)}, which also checks every
 * result against the request.
 *
 * <pre>{@code
 * LLMConfig cfg = LLMConfig.builder().apiKey(System.getenv("JEV_API_KEY")).defaultModel("jev-latest").build();
 * Classifier jev = Classifier.using(new JevProvider(cfg));
 * }</pre>
 *
 * <p>Requests go through {@link HttpClientWrapper}, so retries (429 and 529 "overloaded" are
 * retried by the default policy), timeouts and call telemetry behave as for every other provider.
 * The key is read for each request from the configuration (a literal or a secret), sent
 * only to the configured endpoint as a bearer token, and never appears in an exception message, a
 * log line or an event. Its answers are {@link io.github.llm4j.classify.Answer#calibrated()
 * calibrated}. The wire format is in one class, {@code JevWire}.
 */
public class JevProvider implements ClassificationProvider {

    private static final Logger logger = LoggerFactory.getLogger(JevProvider.class);
    private static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    private static final String DEFAULT_MODEL = "jev-latest";

    private final LLMConfig config;
    private final HttpClientWrapper httpClient;
    private final String baseUrl;
    private final String model;

    public JevProvider(LLMConfig config) {
        this(
                config,
                new HttpClientWrapper(Objects.requireNonNull(config, "config cannot be null")));
    }

    JevProvider(LLMConfig config, HttpClientWrapper httpClient) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.httpClient = httpClient;
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : DEFAULT_BASE_URL;
        this.model = config.getDefaultModel() != null ? config.getDefaultModel() : DEFAULT_MODEL;
        validate();
    }

    @Override
    public ClassifyResult classify(ClassifyRequest request) {
        String body = JevWire.requestBody(request, model);
        long start = System.nanoTime();
        String reply;
        try {
            logger.debug("Calling Jev with {} question(s)", request.questions().size());
            reply = httpClient.post(baseUrl + "/v1/systemone", body, buildHeaders());
        } catch (InvalidRequestException e) {
            if (Integer.valueOf(422).equals(e.getStatusCode())) {
                // the API echoes the offending value, which can be the state: keep the field and
                // the message only
                throw new InvalidRequestException(
                        "Jev rejected the request: "
                                + JevWire.describeValidationError(e.getResponseBody()),
                        422,
                        JevWire.sanitizeValidationBody(e.getResponseBody()));
            }
            throw e;
        } catch (LLMException e) {
            throw e;
        } catch (RuntimeException e) {
            throw Providers.typed(getProviderName(), "Classification failed", e);
        }
        return JevWire.parse(reply, request, (System.nanoTime() - start) / 1_000_000);
    }

    @Override
    public String getProviderName() {
        return "jev";
    }

    @Override
    public void validate() {
        if (!config.hasApiKey()) {
            throw new AuthenticationException(
                    config.missingApiKeyMessage("Jev API key is required"));
        }
    }

    private Headers buildHeaders() {
        return new Headers.Builder()
                .add("Content-Type", "application/json")
                .add("Authorization", "Bearer " + config.requireApiKey("Jev", baseUrl))
                .build();
    }
}
