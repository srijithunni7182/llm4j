package io.github.llm4j.exception;

/** Exception thrown when authentication fails (e.g., invalid API key). */
public class AuthenticationException extends LLMException {

    public AuthenticationException(String message) {
        super(message, 401);
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, cause, 401);
    }

    /** From an HTTP 401/403, keeping the status and the provider's response body. */
    public AuthenticationException(String message, Integer statusCode, String responseBody) {
        super(message, statusCode, responseBody);
    }
}
