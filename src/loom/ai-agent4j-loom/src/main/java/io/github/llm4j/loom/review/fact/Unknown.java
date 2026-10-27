package io.github.llm4j.loom.review.fact;

/**
 * The value of a fact that cannot be known here (no coverage report, nothing declared). A condition
 * on it is neither true nor false.
 */
public final class Unknown {

    public static final Unknown INSTANCE = new Unknown();

    private Unknown() {}

    @Override
    public String toString() {
        return "unknown";
    }
}
