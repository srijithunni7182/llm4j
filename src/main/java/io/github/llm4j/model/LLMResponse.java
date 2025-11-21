package io.github.llm4j.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a response from an LLM.
 * This class is immutable and thread-safe.
 */
public final class LLMResponse {
    
    public enum FinishReason {
        STOP("stop"),
        LENGTH("length"),
        CONTENT_FILTER("content_filter"),
        TOOL_CALLS("tool_calls"),
        ERROR("error"),
        UNKNOWN("unknown");
        
        private final String value;
        
        FinishReason(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public static FinishReason fromValue(String value) {
            if (value == null) {
                return UNKNOWN;
            }
            for (FinishReason reason : values()) {
                if (reason.value.equalsIgnoreCase(value)) {
                    return reason;
                }
            }
            return UNKNOWN;
        }
    }
    
    private final String content;
    private final String model;
    private final TokenUsage tokenUsage;
    private final FinishReason finishReason;
    private final Map<String, Object> metadata;
    
    private LLMResponse(Builder builder) {
        this.content = builder.content;
        this.model = builder.model;
        this.tokenUsage = builder.tokenUsage;
        this.finishReason = builder.finishReason != null ? builder.finishReason : FinishReason.UNKNOWN;
        this.metadata = builder.metadata != null ?
            Collections.unmodifiableMap(new HashMap<>(builder.metadata)) : Collections.emptyMap();
    }
    
    public String getContent() {
        return content;
    }
    
    public String getModel() {
        return model;
    }
    
    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }
    
    public FinishReason getFinishReason() {
        return finishReason;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LLMResponse that = (LLMResponse) o;
        return Objects.equals(content, that.content) &&
               Objects.equals(model, that.model) &&
               Objects.equals(tokenUsage, that.tokenUsage) &&
               finishReason == that.finishReason &&
               Objects.equals(metadata, that.metadata);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(content, model, tokenUsage, finishReason, metadata);
    }
    
    @Override
    public String toString() {
        return "LLMResponse{" +
                "content='" + content + '\'' +
                ", model='" + model + '\'' +
                ", tokenUsage=" + tokenUsage +
                ", finishReason=" + finishReason +
                ", metadata=" + metadata +
                '}';
    }
    
    /**
     * Represents token usage information for a request/response.
     */
    public static final class TokenUsage {
        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;
        
        public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
        
        public int getPromptTokens() {
            return promptTokens;
        }
        
        public int getCompletionTokens() {
            return completionTokens;
        }
        
        public int getTotalTokens() {
            return totalTokens;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TokenUsage that = (TokenUsage) o;
            return promptTokens == that.promptTokens &&
                   completionTokens == that.completionTokens &&
                   totalTokens == that.totalTokens;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(promptTokens, completionTokens, totalTokens);
        }
        
        @Override
        public String toString() {
            return "TokenUsage{" +
                    "promptTokens=" + promptTokens +
                    ", completionTokens=" + completionTokens +
                    ", totalTokens=" + totalTokens +
                    '}';
        }
    }
    
    public static final class Builder {
        private String content;
        private String model;
        private TokenUsage tokenUsage;
        private FinishReason finishReason;
        private Map<String, Object> metadata;
        
        private Builder() {
        }
        
        public Builder content(String content) {
            this.content = content;
            return this;
        }
        
        public Builder model(String model) {
            this.model = model;
            return this;
        }
        
        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = tokenUsage;
            return this;
        }
        
        public Builder tokenUsage(int promptTokens, int completionTokens, int totalTokens) {
            this.tokenUsage = new TokenUsage(promptTokens, completionTokens, totalTokens);
            return this;
        }
        
        public Builder finishReason(FinishReason finishReason) {
            this.finishReason = finishReason;
            return this;
        }
        
        public Builder finishReason(String finishReason) {
            this.finishReason = FinishReason.fromValue(finishReason);
            return this;
        }
        
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }
        
        public Builder addMetadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }
        
        public LLMResponse build() {
            return new LLMResponse(this);
        }
    }
}
