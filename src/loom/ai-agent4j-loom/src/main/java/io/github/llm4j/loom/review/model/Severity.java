package io.github.llm4j.loom.review.model;

import java.util.Locale;
import java.util.Optional;

/** How much a finding matters, most serious first. The same scale the security audit uses. */
public enum Severity {
    HIGH,
    MEDIUM,
    LOW,
    INFO;

    /** True when this severity is at least as serious as {@code threshold}. */
    public boolean atLeast(Severity threshold) {
        return compareTo(threshold) <= 0;
    }

    /** The lower-case word used in reports and settings: {@code high}, {@code medium}, … */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Reads a severity word, ignoring case; empty when it is not one. */
    public static Optional<Severity> parse(String word) {
        if (word == null) return Optional.empty();
        for (Severity s : values())
            if (s.word().equalsIgnoreCase(word.trim())) return Optional.of(s);
        return Optional.empty();
    }
}
