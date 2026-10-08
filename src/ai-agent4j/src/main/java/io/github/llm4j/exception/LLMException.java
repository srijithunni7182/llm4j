package io.github.llm4j.exception;

/** Base exception for all LLM-related errors. */
public class LLMException extends RuntimeException {

    private final Integer statusCode;
    private String responseBody;

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

    /** A failed HTTP call: its status and the provider's response body (kept for diagnosis). */
    public LLMException(String message, Integer statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    /** The provider's response body when this came from a failed HTTP call, else null. */
    public String getResponseBody() {
        return responseBody;
    }

    /** Attaches the provider's response body (used by exception subclasses built from HTTP errors). */
    protected void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }
}
