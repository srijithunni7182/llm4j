package io.github.llm4j.loom.review.cost;

/**
 * Turns text length into tokens the way the runtime guard does (about 4 characters per token plus a
 * 10 % margin), so what review estimates and what a run refuses agree. A test pins this to the
 * runtime's own estimator.
 */
public final class Tokens {

    private Tokens() {}

    /** ceil(chars / 4 x 1.1), in integers. */
    public static long ofChars(long chars) {
        return (Math.max(chars, 0) * 11 + 39) / 40;
    }

    public static long of(String text) {
        return text == null ? 0 : ofChars(text.length());
    }
}
