package io.github.llm4j.config;

import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.secret.SecretException;
import io.github.llm4j.secret.SecretMetadata;
import io.github.llm4j.secret.SecretRef;
import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for LLM client behavior including timeouts, retries, and defaults. This class is
 * immutable and thread-safe.
 */
public final class LLMConfig {

    private final SecretRef apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final Duration connectTimeout;
    private final RetryPolicy retryPolicy;
    private final String defaultModel;
    private final boolean enableLogging;

    private LLMConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.timeout = builder.timeout != null ? builder.timeout : Duration.ofSeconds(60);
        this.connectTimeout =
                builder.connectTimeout != null ? builder.connectTimeout : Duration.ofSeconds(10);
        this.retryPolicy =
                builder.retryPolicy != null ? builder.retryPolicy : RetryPolicy.defaultPolicy();
        this.defaultModel = builder.defaultModel;
        this.enableLogging = builder.enableLogging;
    }

    /**
     * The API key, fetched now (from the secret store when one was given), or null when none was set. No host check: providers use
     * {@link #getApiKey(String)} or {@link #requireApiKey(String, String)} so a key bound to hosts is refused for any other.
     */
    public String getApiKey() {
        return apiKey == null ? null : apiKey.resolve();
    }

    /** The API key for a request to {@code host}: a secret restricted to other hosts is refused. Null when no key was set. */
    public String getApiKey(String host) {
        return apiKey == null ? null : apiKey.resolveFor(host);
    }

    /** The reference the key is held by, or null. It is never the value. */
    public SecretRef getApiKeyRef() {
        return apiKey;
    }

    /** True when a key is set and, for a secret, it exists. Does not hand the value out. */
    public boolean hasApiKey() {
        if (apiKey == null) return false;
        return apiKey.isLiteral() ? !apiKey.resolve().isBlank() : apiKey.exists();
    }

    /** What to say when {@link #hasApiKey()} is false: {@code defaultMessage}, naming the secret when one was given and is missing. */
    public String missingApiKeyMessage(String defaultMessage) {
        if (apiKey != null && !apiKey.isLiteral()) return defaultMessage + ": secret " + apiKey.name() + " is not in the store";
        return defaultMessage;
    }

    /**
     * The key to send to the host of {@code baseUrl}, fetched for this request. A missing key, a missing secret or a host the secret is not allowed
     * for all surface as {@link AuthenticationException}, the type providers are documented to throw. Never put the result in a log or a message.
     */
    public String requireApiKey(String provider, String baseUrl) {
        if (apiKey == null) throw new AuthenticationException(provider + " API key is required");
        String value;
        try {
            value = apiKey.resolveFor(SecretMetadata.hostOf(baseUrl));
        } catch (SecretException e) {
            throw new AuthenticationException(provider + " API key unavailable: " + e.getMessage(), e);
        }
        if (value == null || value.isBlank()) throw new AuthenticationException(provider + " API key is required");
        return value;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public boolean isEnableLogging() {
        return enableLogging;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LLMConfig llmConfig = (LLMConfig) o;
        return enableLogging == llmConfig.enableLogging
                && Objects.equals(apiKey, llmConfig.apiKey)
                && Objects.equals(baseUrl, llmConfig.baseUrl)
                && Objects.equals(timeout, llmConfig.timeout)
                && Objects.equals(connectTimeout, llmConfig.connectTimeout)
                && Objects.equals(retryPolicy, llmConfig.retryPolicy)
                && Objects.equals(defaultModel, llmConfig.defaultModel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                apiKey, baseUrl, timeout, connectTimeout, retryPolicy, defaultModel, enableLogging);
    }

    @Override
    public String toString() {
        return "LLMConfig{"
                + "apiKey="
                + (apiKey != null ? apiKey.toString() : "null")
                + ", baseUrl='"
                + baseUrl
                + '\''
                + ", timeout="
                + timeout
                + ", connectTimeout="
                + connectTimeout
                + ", retryPolicy="
                + retryPolicy
                + ", defaultModel='"
                + defaultModel
                + '\''
                + ", enableLogging="
                + enableLogging
                + '}';
    }

    public static final class Builder {
        private SecretRef apiKey;
        private String baseUrl;
        private Duration timeout;
        private Duration connectTimeout;
        private RetryPolicy retryPolicy;
        private String defaultModel;
        private boolean enableLogging = false;

        private Builder() {}

        /** A key held in memory as given. For anything you keep, prefer {@link #apiKey(SecretRef)}. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey == null ? null : SecretRef.literal(apiKey);
            return this;
        }

        /** A key kept in a {@link io.github.llm4j.secret.SecretStore}, fetched for each request and never held by the config. */
        public Builder apiKey(SecretRef apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder defaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        public Builder enableLogging(boolean enableLogging) {
            this.enableLogging = enableLogging;
            return this;
        }

        public LLMConfig build() {
            return new LLMConfig(this);
        }
    }
}
