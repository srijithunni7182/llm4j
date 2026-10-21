package io.github.llm4j.loom.inspect.map;

/**
 * How much of what an agent said or a tool returned may leave the process. Applied in the workflow
 * process, before anything is written to the outbox or sent, so a receiver never sees more than its
 * level allows.
 */
public enum Detail {
    /** Everything, as the GenAI conventions' opt-in content attributes. */
    FULL,
    /** Cut to {@value #LIMIT} characters (the default). */
    TRUNCATED,
    /** No text at all: types, agents, sizes and timings only. */
    METADATA;

    public static final int LIMIT = 500;

    /** The text as this level allows it, or null when it allows none. */
    public String apply(String text) {
        if (text == null || text.isEmpty()) return null;
        return switch (this) {
            case FULL -> text;
            case TRUNCATED -> text.length() <= LIMIT ? text : text.substring(0, LIMIT) + "…";
            case METADATA -> null;
        };
    }

    public static Detail parse(String word) {
        if (word == null || word.isBlank()) return TRUNCATED;
        return switch (word.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "full" -> FULL;
            case "truncated" -> TRUNCATED;
            case "metadata" -> METADATA;
            default -> throw new IllegalArgumentException(
                    "detail must be full, truncated or metadata, got '" + word + "'");
        };
    }
}
