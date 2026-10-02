package io.github.llm4j.loom.security;

/** How much a finding matters, most serious first. */
public enum Severity {
    HIGH, MEDIUM, LOW, INFO;

    public boolean atLeast(Severity threshold) {
        return compareTo(threshold) <= 0;
    }

    public String word() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
