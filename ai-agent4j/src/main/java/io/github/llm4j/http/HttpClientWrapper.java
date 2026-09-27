package io.github.llm4j.http;

import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.ratelimit.RateLimitInfo;
import io.github.llm4j.ratelimit.RateLimitParser;
import io.github.llm4j.ratelimit.RateLimitParsers;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wrapper around OkHttp client with retry logic and logging. */
public class HttpClientWrapper {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientWrapper.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final RetryPolicy retryPolicy;
    private final boolean enableLogging;
    private final RateLimitParsers rateLimits;

    public HttpClientWrapper(
            Duration timeout,
            Duration connectTimeout,
            RetryPolicy retryPolicy,
            boolean enableLogging) {
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy cannot be null");
        this.enableLogging = enableLogging;
        this.rateLimits = RateLimitParsers.standard(retryPolicy.getDailyResetZone(),
                retryPolicy.getFallbackDelay(), retryPolicy.getMaxFallbackDelay());
        this.client =
                new OkHttpClient.Builder()
                        .callTimeout(timeout)
                        .connectTimeout(connectTimeout)
                        .readTimeout(timeout)
                        .writeTimeout(timeout)
                        .build();
    }

    public HttpClientWrapper(io.github.llm4j.config.LLMConfig config) {
        this(
                config.getTimeout(),
                config.getConnectTimeout(),
                config.getRetryPolicy(),
                config.isEnableLogging());
    }

    /**
     * Executes an HTTP POST request with retry logic.
     *
     * @param url the request URL
     * @param jsonBody the JSON request body
     * @param headers additional headers to include
     * @return the response body as a string
     * @throws LLMException if the request fails after all retries
     */
    public String post(String url, String jsonBody, Headers headers) {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder().url(url).headers(headers).post(body).build();

        return executeWithRetry(request);
    }

    /**
     * Executes an HTTP GET request with retry logic.
     *
     * @param url the request URL
     * @param headers additional headers to include
     * @return the response body as a string
     * @throws LLMException if the request fails after all retries
     */
    public String get(String url, Headers headers) {
        Request request = new Request.Builder().url(url).headers(headers).get().build();

        return executeWithRetry(request);
    }

    /**
     * Creates an OkHttp call for streaming responses.
     *
     * @param url the request URL
     * @param jsonBody the JSON request body
     * @param headers additional headers to include
     * @return the OkHttp Call object
     */
    public Call createStreamingCall(String url, String jsonBody, Headers headers) {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder().url(url).headers(headers).post(body).build();

        return client.newCall(request);
    }

    /**
     * Executes an HTTP POST request with multipart/form-data.
     *
     * @param url the request URL
     * @param parts the parts to include in the multipart body (String key -> Object value). Values
     *     can be File or String.
     * @param headers additional headers to include
     * @return the response body as a string
     */
    public String postMultipart(String url, java.util.Map<String, Object> parts, Headers headers) {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);

        for (java.util.Map.Entry<String, Object> entry : parts.entrySet()) {
            if (entry.getValue() instanceof java.io.File) {
                java.io.File file = (java.io.File) entry.getValue();
                // Determine content type or default to octet-stream
                MediaType contentType = MediaType.parse("application/octet-stream");
                // We could use Files.probeContentType(path) but for simplicity, let's assume
                // binary or let OkHttp handle it?
                // OkHttp RequestBody.create(file, contentType)
                builder.addFormDataPart(
                        entry.getKey(), file.getName(), RequestBody.create(file, contentType));
            } else if (entry.getValue() instanceof String) {
                builder.addFormDataPart(entry.getKey(), (String) entry.getValue());
            } else {
                if (entry.getValue() != null) {
                    builder.addFormDataPart(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }

        RequestBody body = builder.build();
        Request request = new Request.Builder().url(url).headers(headers).post(body).build();

        return executeWithRetry(request);
    }

    private String executeWithRetry(Request request) {
        int attempt = 0;
        LLMException lastException = null;
        Duration nextWait = null; // set by a 429 that told us when to come back
        String provider = providerOf(request);

        while (attempt <= retryPolicy.getMaxRetries()) {
            try {
                if (attempt > 0) {
                    Duration backoff = nextWait != null ? nextWait : retryPolicy.calculateBackoff(attempt - 1);
                    nextWait = null;
                    if (enableLogging) {
                        logger.info(
                                "Retrying request after {} ms (attempt {}/{})",
                                backoff.toMillis(),
                                attempt,
                                retryPolicy.getMaxRetries());
                    }
                    retryPolicy.getSleeper().sleep(backoff);
                }

                if (enableLogging) {
                    logger.debug("Executing HTTP {} to {}", request.method(), request.url());
                }

                try (Response response = client.newCall(request).execute()) {
                    ResponseBody responseBody = response.body();
                    String bodyString = responseBody != null ? responseBody.string() : "";

                    if (!response.isSuccessful()) {
                        int statusCode = response.code();

                        if (enableLogging) {
                            logger.warn(
                                    "HTTP request failed with status {}: {}",
                                    statusCode,
                                    bodyString);
                        }

                        if (statusCode == 429) {
                            Instant now = retryPolicy.getClock().instant();
                            RateLimitInfo info = rateLimits.parse(
                                    new RateLimitParser.Response(provider, statusCode,
                                            response.headers().toMultimap(), bodyString),
                                    retryPolicy.getClock());
                            Duration wait = info.waitFrom(now);
                            if (attempt < retryPolicy.getMaxRetries()
                                    && retryPolicy.isRetryable(statusCode)
                                    && wait.compareTo(retryPolicy.getInlineWaitThreshold()) <= 0) {
                                nextWait = wait.plus(jitter(wait));
                                lastException = new RateLimitException(info, now, bodyString);
                                attempt++;
                                continue;
                            }
                            throw new RateLimitException(info, now, bodyString);
                        }

                        // Check if we should retry
                        if (attempt < retryPolicy.getMaxRetries()
                                && retryPolicy.isRetryable(statusCode)) {
                            lastException =
                                    new LLMException(
                                            "HTTP request failed with status "
                                                    + statusCode
                                                    + ": "
                                                    + bodyString,
                                            statusCode);
                            attempt++;
                            continue;
                        }

                        // No more retries, throw exception
                        throw new LLMException(
                                "HTTP request failed with status " + statusCode + ": " + bodyString,
                                statusCode);
                    }

                    if (enableLogging) {
                        logger.debug("HTTP request succeeded with status {}", response.code());
                    }
                    rateLimits.success(provider);
                    return bodyString;
                }
            } catch (IOException e) {
                if (enableLogging) {
                    logger.error("HTTP request failed with IOException", e);
                }

                lastException = new LLMException("HTTP request failed: " + e.getMessage(), e);

                if (attempt >= retryPolicy.getMaxRetries()) {
                    throw lastException;
                }

                attempt++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LLMException("Request interrupted", e);
            }
        }

        throw lastException != null
                ? lastException
                : new LLMException(
                        "Request failed after " + retryPolicy.getMaxRetries() + " retries");
    }

    /** Up to 10% extra, so many clients throttled together don't all return in the same instant. */
    private static Duration jitter(Duration wait) {
        long max = wait.toMillis() / 10;
        return max <= 0 ? Duration.ZERO : Duration.ofMillis(ThreadLocalRandom.current().nextLong(max + 1));
    }

    /** A short provider name from the request host, for rate-limit reports. */
    static String providerOf(Request request) {
        String host = request.url().host().toLowerCase(Locale.ROOT);
        if (host.contains("googleapis") || host.contains("google")) return "google";
        if (host.contains("anthropic")) return "anthropic";
        if (host.contains("openai")) return "openai";
        if (host.contains("sarvam")) return "sarvam";
        if (request.url().port() == 11434 || host.contains("ollama")) return "ollama";
        return host;
    }

    /** Closes the HTTP client and releases resources. */
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
