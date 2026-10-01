package io.github.llm4j.loom.tools.generic;

/** An option with a value the kind can't use; the message is what the script author sees at load. */
public final class OptionException extends IllegalArgumentException {

    public OptionException(String message) {
        super(message);
    }
}
