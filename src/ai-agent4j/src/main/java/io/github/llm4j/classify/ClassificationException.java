package io.github.llm4j.classify;

import io.github.llm4j.exception.LLMException;

/**
 * A backend replied, but not with an answer the request allows: a label that was not offered, a
 * missing or extra question, a probability outside [0,1], a reply cut off by the output cap, or
 * text that is not the JSON that was asked for. Nothing is returned in that case, never a guess.
 * The message names what was wrong, never the state or the key.
 */
public class ClassificationException extends LLMException {

    public ClassificationException(String message) {
        super(message);
    }

    public ClassificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
