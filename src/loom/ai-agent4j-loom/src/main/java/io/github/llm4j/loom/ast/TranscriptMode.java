package io.github.llm4j.loom.ast;

import java.util.Locale;

/**
 * How much of the run's shared transcript an agent is briefed with, set by {@code memory {
 * transcript: none | own | all }} (docs/specs/24-stateless-nodes, R8). Whatever the mode, the
 * agent's own exchanges are still stored, so other agents with {@link #ALL} still see them.
 */
public enum TranscriptMode {
    /** Everything said before it, by every agent. The default. */
    ALL,
    /** Only this agent's own earlier exchanges in the run. */
    OWN,
    /** No transcript: its task is the whole briefing. */
    NONE;

    /** The words a script uses, in the order they are listed in messages. */
    public static final String WORDS = "none, own, all";

    /**
     * The mode written as {@code word}, or null when it is not one of {@link #WORDS}.
     *
     * @param word the word after {@code transcript:}
     */
    public static TranscriptMode of(String word) {
        if (word == null) return null;
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "all" -> ALL;
            case "own" -> OWN;
            case "none" -> NONE;
            default -> null;
        };
    }
}
