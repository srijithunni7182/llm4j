package io.github.llm4j.fairness;

/** Severity level of detected bias. */
public enum BiasSeverity {
    /** Minor bias that may be acceptable in context. */
    LOW,

    /** Moderate bias that should be reviewed. */
    MEDIUM,

    /** Significant bias that requires attention. */
    HIGH,

    /** Critical bias that must be addressed immediately. */
    CRITICAL
}
