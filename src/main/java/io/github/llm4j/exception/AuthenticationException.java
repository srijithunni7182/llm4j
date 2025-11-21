package io.github.llm4j.exception;

/**
 * Exception thrown when authentication fails (e.g., invalid API key).
 */
public class AuthenticationException extends LLMException {
    
    public AuthenticationException(String message) {
        super(message, 401);
    }
    
    public AuthenticationException(String message, Throwable cause) {
        super(message, cause, 401);
    }
}
