package io.github.llm4j.loom.tools.generic;

/** The action may or may not have happened (a timeout after sending, a connection lost after the data). */
public final class UnknownOutcomeException extends ToolRefusal {

    public UnknownOutcomeException(String message) {
        super(message);
    }

    public UnknownOutcomeException(String message, Throwable cause) {
        super(message, cause);
    }
}
