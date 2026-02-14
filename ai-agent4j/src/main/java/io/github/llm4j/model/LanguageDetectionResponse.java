package io.github.llm4j.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Standardized response for Language Detection.
 */
public class LanguageDetectionResponse {

    private final String detectedLanguageCode;
    private final String detectedScript;
    private final Double confidenceScore;

    public LanguageDetectionResponse(String detectedLanguageCode, String detectedScript, Double confidenceScore) {
        this.detectedLanguageCode = Objects.requireNonNull(detectedLanguageCode,
                "Detected language code cannot be null");
        this.detectedScript = detectedScript;
        this.confidenceScore = confidenceScore;
    }

    public String getDetectedLanguageCode() {
        return detectedLanguageCode;
    }

    public Optional<String> getDetectedScript() {
        return Optional.ofNullable(detectedScript);
    }

    public Optional<Double> getConfidenceScore() {
        return Optional.ofNullable(confidenceScore);
    }

    @Override
    public String toString() {
        return "LanguageDetectionResponse{" +
                "detectedLanguageCode='" + detectedLanguageCode + '\'' +
                ", detectedScript='" + detectedScript + '\'' +
                ", confidenceScore=" + confidenceScore +
                '}';
    }
}
