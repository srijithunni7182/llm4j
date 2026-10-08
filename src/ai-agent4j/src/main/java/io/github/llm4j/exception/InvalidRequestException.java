package io.github.llm4j.exception;

/** Exception thrown when a request is invalid or malformed. */
public class InvalidRequestException extends LLMException {

    public InvalidRequestException(String message) {
        super(message, 400);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause, 400);
    }

    /** From an HTTP 4xx, keeping the status and the provider's response body. */
    public InvalidRequestException(String message, Integer statusCode, String responseBody) {
        super(message, statusCode, responseBody);
    }
}
