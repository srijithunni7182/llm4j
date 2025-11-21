package io.github.llm4j.exception;

/**
 * Exception thrown when rate limits are exceeded.
 */
public class RateLimitException extends LLMException {
    
    private final Long retryAfterSeconds;
    
    public RateLimitException(String message) {
        super(message, 429);
        this.retryAfterSeconds = null;
    }
    
    public RateLimitException(String message, Long retryAfterSeconds) {
        super(message, 429);
        this.retryAfterSeconds = retryAfterSeconds;
    }
    
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
