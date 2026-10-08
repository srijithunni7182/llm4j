package io.github.llm4j.privacy;

/**
 * Interface for detecting and masking Personally Identifiable Information (PII) in text. Supports
 * xAI compliance by enabling privacy-preserving data handling.
 */
public interface PIIDetector {

    /**
     * Detects PII in the given text.
     *
     * @param text the text to analyze
     * @return detection result containing found PII entities
     */
    PIIDetectionResult detect(String text);

    /**
     * Masks PII in the given text using the specified strategy.
     *
     * @param text the text containing PII
     * @param strategy the masking strategy to use
     * @return text with PII masked
     */
    String mask(String text, MaskingStrategy strategy);

    /**
     * Convenience method to mask PII using PLACEHOLDER strategy.
     *
     * @param text the text containing PII
     * @return text with PII replaced by type placeholders
     */
    default String mask(String text) {
        return mask(text, MaskingStrategy.PLACEHOLDER);
    }
}
