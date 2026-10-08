package io.github.llm4j.privacy;

/** Strategy for masking PII in text. */
public enum MaskingStrategy {
    /** Replace entire PII with asterisks. Example: "john@example.com" -> "***@***.***" */
    FULL,

    /**
     * Keep first and last character, mask middle. Example: "john@example.com" -> "j***@e***.c**"
     */
    PARTIAL,

    /** Replace with placeholder indicating type. Example: "john@example.com" -> "[EMAIL]" */
    PLACEHOLDER
}
