package io.github.llm4j.http;

import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.ServiceUnavailableException;
import java.util.List;
import java.util.Map;

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
        return send(request, client, response -> {
            try (response) {
                ResponseBody body = response.body();
                return body != null ? body.string() : "";
            }
        });
    }

    /** What to do with a successful (2xx) response; it owns the response from then on. */
    @FunctionalInterface
    private interface OnSuccess<T> {
        T accept(Response response) throws IOException;
    }

    /**
     * Sends with retries: retryable statuses and I/O errors are retried with backoff (a 429 waits as
     * long as the provider says, when that's short enough); a final failure is a typed {@link
     * LLMException} (see {@link #failure}). Nothing is retried once a 2xx arrives.
     */
    private <T> T send(Request request, OkHttpClient http, OnSuccess<T> onSuccess) {
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
                    logger.debug("Executing HTTP {} to {}", request.method(), request.url().redact());
                }

                Response response = http.newCall(request).execute();
                if (response.isSuccessful()) {
                    if (enableLogging) {
                        logger.debug("HTTP request succeeded with status {}", response.code());
                    }
                    rateLimits.success(provider);
                    return onSuccess.accept(response);
                }

                int statusCode = response.code();
                String bodyString;
                Map<String, List<String>> headers = response.headers().toMultimap();
                try (response) {
                    ResponseBody responseBody = response.body();
                    bodyString = responseBody != null ? responseBody.string() : "";
                }
                // a provider may echo what it was sent: nothing downstream (messages, logs, rate-limit parsing) sees the credential
                bodyString = Credentials.scrub(bodyString, request.headers());

                if (enableLogging) {
                    logger.warn("HTTP request failed with status {}: {}", statusCode, bodyString);
                }

                if (statusCode == 429) {
                    Instant now = retryPolicy.getClock().instant();
                    RateLimitInfo info = rateLimits.parse(
                            new RateLimitParser.Response(provider, statusCode, headers, bodyString),
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

                LLMException failure = failure(provider, statusCode, bodyString, headers);
                if (attempt < retryPolicy.getMaxRetries() && retryPolicy.isRetryable(statusCode)) {
                    lastException = failure;
                    attempt++;
                    continue;
                }
                throw failure;
            } catch (IOException e) {
                if (enableLogging) {
                    logger.error("HTTP request failed with IOException", e);
                }

                lastException = new LLMException("HTTP request failed: " + Credentials.scrub(e.getMessage(), request.headers()), e);

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

    private static final com.fasterxml.jackson.databind.ObjectMapper ERRORS = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * The same failure reads the same whatever the provider: 401/403 → {@link AuthenticationException},
     * 400/404/413/422 → {@link InvalidRequestException}, 500/502/503/504/529 → {@link
     * ServiceUnavailableException}, anything else → {@link LLMException}. All keep the status and the
     * response body; the message carries the provider's own error text and request id (never headers
     * that could hold credentials).
     */
    static LLMException failure(String provider, int status, String body, Map<String, List<String>> headers) {
        String message = "HTTP request failed with status " + status + ": " + describe(body) + requestId(headers, body);
        return switch (status) {
            case 401, 403 -> new AuthenticationException(message, status, body);
            case 400, 404, 413, 422 -> new InvalidRequestException(message, status, body);
            case 500, 502, 503, 504, 529 -> new ServiceUnavailableException(provider, message, status, body);
            default -> new LLMException(message, status, body);
        };
    }

    /** The provider's error text from its usual JSON shapes, else the (shortened) body itself. */
    static String describe(String body) {
        if (body == null || body.isBlank()) return "(no body)";
        try {
            com.fasterxml.jackson.databind.JsonNode root = ERRORS.readTree(body);
            if (root.isArray() && root.size() > 0) root = root.get(0); // Gemini sometimes wraps errors in a list
            com.fasterxml.jackson.databind.JsonNode error = root.path("error");
            if (error.isObject()) {
                String type = error.path("type").asText(error.path("status").asText(""));
                String text = error.path("message").asText("");
                if (!text.isEmpty()) return type.isEmpty() ? text : type + ": " + text;
            } else if (error.isTextual()) {
                return error.asText();
            }
            if (root.path("message").isTextual()) return root.path("message").asText();
        } catch (IOException | RuntimeException notJson) {
            // fall through: not JSON
        }
        String trimmed = body.strip();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) + "…" : trimmed;
    }

    /** The provider's request id: a response header, or (Anthropic, on some errors) a body field. */
    private static String requestId(Map<String, List<String>> headers, String body) {
        String fromHeader = requestId(headers);
        if (!fromHeader.isEmpty() || body == null || !body.contains("request_id")) return fromHeader;
        try {
            com.fasterxml.jackson.databind.JsonNode id = ERRORS.readTree(body).path("request_id");
            return id.isTextual() ? " (request-id " + id.asText() + ")" : "";
        } catch (IOException | RuntimeException notJson) {
            return "";
        }
    }

    private static String requestId(Map<String, List<String>> headers) {
        if (headers == null) return "";
        for (String name : List.of("request-id", "x-request-id", "x-goog-request-id")) {
            for (Map.Entry<String, List<String>> h : headers.entrySet()) {
                if (h.getKey() != null && h.getKey().equalsIgnoreCase(name) && !h.getValue().isEmpty()) {
                    return " (" + name + " " + h.getValue().get(0) + ")";
                }
            }
        }
        return "";
    }

    /**
     * Opens a streaming POST. Failures before the stream starts are retried and reported exactly as
     * for {@link #post}; once a 2xx arrives the body is handed over, unread. Read it with {@link
     * StreamingBody#events()} (server-sent events) or {@link StreamingBody#lines()} (NDJSON), and
     * close it (or exhaust it) to release the connection.
     */
    public StreamingBody stream(String url, String jsonBody, Headers headers) {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder().url(url).headers(headers).post(body).build();
        // No overall deadline for a stream (a long answer is fine); the read timeout still catches a stall.
        OkHttpClient streaming = client.newBuilder().callTimeout(Duration.ZERO).build();
        return send(request, streaming, StreamingBody::new);
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
