package io.github.llm4j.context;

import java.util.OptionalDouble;

/**
 * How much of the context a prompt uses.
 *
 * @param used prompt tokens
 * @param window the model's window, or null when it is not known
 * @param source whether {@code used} was reported by the provider or estimated
 */
public record ContextUsage(long used, ContextWindow window, Source source) {

    public enum Source {
        /**
         * The provider's own count from its last reply, plus an estimate of what was added since.
         */
        REPORTED,
        /** Estimated from the text. */
        ESTIMATED
    }

    public ContextUsage {
        if (used < 0) throw new IllegalArgumentException("used must not be negative, got " + used);
        if (source == null) throw new IllegalArgumentException("source must be given");
    }

    /**
     * Used tokens over the usable input size, which can exceed 1.0; empty when the window is not
     * known.
     */
    public OptionalDouble fraction() {
        return window == null
                ? OptionalDouble.empty()
                : OptionalDouble.of(used / (double) window.usableInput());
    }
}
