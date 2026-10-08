package io.github.llm4j.loom.autonomy;

/** How much freedom an agent has on a decision. The order is the order of the ladder. */
public enum Level {
    /** The agent proposes quietly; people decide as they always did and never see the proposal. */
    WATCH,
    /** People see the proposal and confirm or change it. */
    SUGGEST,
    /** The proposal takes effect; a person still checks a sample. */
    ACT;

    public String word() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The level written as a word, or null. */
    public static Level of(String word) {
        if (word == null) return null;
        for (Level l : values()) if (l.word().equals(word.trim().toLowerCase(java.util.Locale.ROOT))) return l;
        return null;
    }

    public boolean atLeast(Level other) {
        return compareTo(other) >= 0;
    }

    public static Level min(Level a, Level b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
