package io.github.llm4j.exception;

/**
 * Base exception for all LLM-related errors.
 */
public class LLMException extends RuntimeException {
    
    private final Integer statusCode;
    
    public LLMException(String message) {
        super(message);
        this.statusCode = null;
    }
    
    public LLMException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
    }
    
    public LLMException(String message, Integer statusCode) {
        super(message);
        this.statusCode = statusCode;
    }
    
    public LLMException(String message, Throwable cause, Integer statusCode) {
        super(message, cause);
        this.statusCode = statusCode;
    }
    
    public Integer getStatusCode() {
        return statusCode;
    }
}
