package io.github.llm4j.context;

/**
 * A model's context window in tokens, and how much of it to keep free for the model's reply.
 *
 * @param tokens the whole window (prompt plus reply)
 * @param reservedForOutput tokens kept for the reply; the prompt may use {@link #usableInput()}
 */
public record ContextWindow(int tokens, int reservedForOutput) {

    public ContextWindow {
        if (tokens <= 0)
            throw new IllegalArgumentException("tokens must be positive, got " + tokens);
        if (reservedForOutput < 0 || reservedForOutput >= tokens) {
            throw new IllegalArgumentException(
                    "reservedForOutput must be between 0 and "
                            + (tokens - 1)
                            + ", got "
                            + reservedForOutput);
        }
    }

    public static ContextWindow of(int tokens) {
        return new ContextWindow(tokens, 0);
    }

    public ContextWindow reservingForOutput(int reserved) {
        return new ContextWindow(tokens, reserved);
    }

    /** What the prompt may use: the window less the reserve for the reply. */
    public int usableInput() {
        return tokens - reservedForOutput;
    }
}
