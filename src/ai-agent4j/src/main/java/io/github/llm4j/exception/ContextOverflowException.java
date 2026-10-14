package io.github.llm4j.exception;

/**
 * The prompt is longer than the model's context window: a provider rejected it as too long, or the agent saw that it could not fit. It is an
 * {@link InvalidRequestException}, so code that handles bad requests still catches it; catch this type to react to the size in particular
 * (shorten the prompt, summarise, use a model with a larger window). An agent configured with a compaction strategy compacts harder and tries
 * once more before this reaches the caller.
 */
public class ContextOverflowException extends InvalidRequestException {

    /** Raised by the agent itself, with no provider response involved. */
    public ContextOverflowException(String message) {
        super(message);
    }

    /** From a provider's rejection, keeping its status and body. */
    public ContextOverflowException(String message, Integer statusCode, String responseBody) {
        super(message, statusCode == null ? Integer.valueOf(400) : statusCode, responseBody);
    }
}
