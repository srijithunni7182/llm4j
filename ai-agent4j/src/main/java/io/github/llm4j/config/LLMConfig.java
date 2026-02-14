package io.github.llm4j.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for LLM client behavior including timeouts, retries, and defaults.
 * This class is immutable and thread-safe.
 */
public final class LLMConfig {
    
    private final String apiKey;
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
        this.connectTimeout = builder.connectTimeout != null ? builder.connectTimeout : Duration.ofSeconds(10);
        this.retryPolicy = builder.retryPolicy != null ? builder.retryPolicy : RetryPolicy.defaultPolicy();
        this.defaultModel = builder.defaultModel;
        this.enableLogging = builder.enableLogging;
    }
    
    public String getApiKey() {
        return apiKey;
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
        return enableLogging == llmConfig.enableLogging &&
               Objects.equals(apiKey, llmConfig.apiKey) &&
               Objects.equals(baseUrl, llmConfig.baseUrl) &&
               Objects.equals(timeout, llmConfig.timeout) &&
               Objects.equals(connectTimeout, llmConfig.connectTimeout) &&
               Objects.equals(retryPolicy, llmConfig.retryPolicy) &&
               Objects.equals(defaultModel, llmConfig.defaultModel);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(apiKey, baseUrl, timeout, connectTimeout, retryPolicy, defaultModel, enableLogging);
    }
    
    @Override
    public String toString() {
        return "LLMConfig{" +
                "apiKey=" + (apiKey != null ? "***" : "null") +
                ", baseUrl='" + baseUrl + '\'' +
                ", timeout=" + timeout +
                ", connectTimeout=" + connectTimeout +
                ", retryPolicy=" + retryPolicy +
                ", defaultModel='" + defaultModel + '\'' +
                ", enableLogging=" + enableLogging +
                '}';
    }
    
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private Duration timeout;
        private Duration connectTimeout;
        private RetryPolicy retryPolicy;
        private String defaultModel;
        private boolean enableLogging = false;
        
        private Builder() {
        }
        
        public Builder apiKey(String apiKey) {
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
