package io.github.llm4j.privacy;

/** Types of Personally Identifiable Information (PII) that can be detected. */
public enum PIIType {
    EMAIL("Email Address"),
    PHONE("Phone Number"),
    SSN("Social Security Number"),
    CREDIT_CARD("Credit Card Number"),
    IP_ADDRESS("IP Address"),
    URL("URL");

    private final String displayName;

    PIIType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
