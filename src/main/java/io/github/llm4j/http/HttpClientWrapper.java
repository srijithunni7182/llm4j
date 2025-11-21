package io.github.llm4j.http;

import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.LLMException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Wrapper around OkHttp client with retry logic and logging.
 */
public class HttpClientWrapper {
    
    private static final Logger logger = LoggerFactory.getLogger(HttpClientWrapper.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient client;
    private final RetryPolicy retryPolicy;
    private final boolean enableLogging;
    
    public HttpClientWrapper(Duration timeout, Duration connectTimeout, RetryPolicy retryPolicy, boolean enableLogging) {
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy cannot be null");
        this.enableLogging = enableLogging;
        this.client = new OkHttpClient.Builder()
                .callTimeout(timeout)
                .connectTimeout(connectTimeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build();
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
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .post(body)
                .build();
        
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
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build();
        
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
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .post(body)
                .build();
        
        return client.newCall(request);
    }
    
    private String executeWithRetry(Request request) {
        int attempt = 0;
        LLMException lastException = null;
        
        while (attempt <= retryPolicy.getMaxRetries()) {
            try {
                if (attempt > 0) {
                    Duration backoff = retryPolicy.calculateBackoff(attempt - 1);
                    if (enableLogging) {
                        logger.info("Retrying request after {} ms (attempt {}/{})", 
                                backoff.toMillis(), attempt, retryPolicy.getMaxRetries());
                    }
                    Thread.sleep(backoff.toMillis());
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
                            logger.warn("HTTP request failed with status {}: {}", statusCode, bodyString);
                        }
                        
                        // Check if we should retry
                        if (attempt < retryPolicy.getMaxRetries() && retryPolicy.isRetryable(statusCode)) {
                            lastException = new LLMException(
                                    "HTTP request failed with status " + statusCode + ": " + bodyString,
                                    statusCode
                            );
                            attempt++;
                            continue;
                        }
                        
                        // No more retries, throw exception
                        throw new LLMException(
                                "HTTP request failed with status " + statusCode + ": " + bodyString,
                                statusCode
                        );
                    }
                    
                    if (enableLogging) {
                        logger.debug("HTTP request succeeded with status {}", response.code());
                    }
                    
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
        
        throw lastException != null ? lastException : 
                new LLMException("Request failed after " + retryPolicy.getMaxRetries() + " retries");
    }
    
    /**
     * Closes the HTTP client and releases resources.
     */
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
