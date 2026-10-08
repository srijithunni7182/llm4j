package io.github.llm4j.model;

import java.util.Objects;

/**
 * Represents a confidence score for an agent decision or step. Provides quantified uncertainty to
 * support xAI compliance.
 */
public class ConfidenceScore {

    private final double score;
    private final ConfidenceLevel level;
    private final String reasoning;

    private ConfidenceScore(Builder builder) {
        if (builder.score < 0.0 || builder.score > 1.0) {
            throw new IllegalArgumentException(
                    "Score must be between 0.0 and 1.0, got: " + builder.score);
        }
        this.score = builder.score;
        this.level = ConfidenceLevel.fromScore(builder.score);
        this.reasoning = builder.reasoning;
    }

    public double getScore() {
        return score;
    }

    public ConfidenceLevel getLevel() {
        return level;
    }

    public String getReasoning() {
        return reasoning;
    }

    public boolean isHigh() {
        return level == ConfidenceLevel.HIGH;
    }

    public boolean isLow() {
        return level == ConfidenceLevel.LOW || level == ConfidenceLevel.UNKNOWN;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ConfidenceScore high(String reasoning) {
        return builder().score(0.9).reasoning(reasoning).build();
    }

    public static ConfidenceScore medium(String reasoning) {
        return builder().score(0.6).reasoning(reasoning).build();
    }

    public static ConfidenceScore low(String reasoning) {
        return builder().score(0.3).reasoning(reasoning).build();
    }

    public static ConfidenceScore unknown(String reasoning) {
        return builder().score(0.1).reasoning(reasoning).build();
    }

    public static class Builder {
        private double score = 0.5; // Default to medium
        private String reasoning;

        public Builder score(double score) {
            this.score = score;
            return this;
        }

        public Builder reasoning(String reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        public ConfidenceScore build() {
            return new ConfidenceScore(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfidenceScore that = (ConfidenceScore) o;
        return Double.compare(that.score, score) == 0
                && level == that.level
                && Objects.equals(reasoning, that.reasoning);
    }

    @Override
    public int hashCode() {
        return Objects.hash(score, level, reasoning);
    }

    @Override
    public String toString() {
        return "ConfidenceScore{"
                + "score="
                + score
                + ", level="
                + level
                + ", reasoning='"
                + reasoning
                + '\''
                + '}';
    }

    /** Confidence level based on score thresholds. */
    public enum ConfidenceLevel {
        HIGH(0.8, 1.0),
        MEDIUM(0.5, 0.8),
        LOW(0.2, 0.5),
        UNKNOWN(0.0, 0.2);

        private final double minScore;
        private final double maxScore;

        ConfidenceLevel(double minScore, double maxScore) {
            this.minScore = minScore;
            this.maxScore = maxScore;
        }

        public double getMinScore() {
            return minScore;
        }

        public double getMaxScore() {
            return maxScore;
        }

        public boolean contains(double score) {
            return score >= minScore && score < maxScore;
        }

        public static ConfidenceLevel fromScore(double score) {
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("Score must be between 0.0 and 1.0");
            }

            for (ConfidenceLevel level : values()) {
                if (level.contains(score)) {
                    return level;
                }
            }

            // Edge case: score == 1.0 should be HIGH
            return HIGH;
        }
    }
}
