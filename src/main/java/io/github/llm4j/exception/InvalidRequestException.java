package io.github.llm4j.exception;

/**
 * Exception thrown when a request is invalid or malformed.
 */
public class InvalidRequestException extends LLMException {
    
    public InvalidRequestException(String message) {
        super(message, 400);
    }
    
    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause, 400);
    }
}
