package io.github.llm4j.fairness;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Represents a detected bias event in agent output. */
public class BiasEvent {
    private final BiasType type;
    private final BiasSeverity severity;
    private final String text;
    private final String explanation;
    private final double confidence;
    private final Instant timestamp;
    private final Map<String, Object> metadata;

    private BiasEvent(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type cannot be null");
        this.severity = Objects.requireNonNull(builder.severity, "severity cannot be null");
        this.text = Objects.requireNonNull(builder.text, "text cannot be null");
        this.explanation = builder.explanation;
        this.confidence = builder.confidence;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.metadata = new HashMap<>(builder.metadata);
    }

    public BiasType getType() {
        return type;
    }

    public BiasSeverity getSeverity() {
        return severity;
    }

    public String getText() {
        return text;
    }

    public String getExplanation() {
        return explanation;
    }

    public double getConfidence() {
        return confidence;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BiasType type;
        private BiasSeverity severity;
        private String text;
        private String explanation;
        private double confidence = 0.5;
        private Instant timestamp;
        private Map<String, Object> metadata = new HashMap<>();

        public Builder type(BiasType type) {
            this.type = type;
            return this;
        }

        public Builder severity(BiasSeverity severity) {
            this.severity = severity;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder explanation(String explanation) {
            this.explanation = explanation;
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = new HashMap<>(metadata);
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public BiasEvent build() {
            return new BiasEvent(this);
        }
    }

    @Override
    public String toString() {
        return "BiasEvent{"
                + "type="
                + type
                + ", severity="
                + severity
                + ", confidence="
                + confidence
                + ", explanation='"
                + explanation
                + '\''
                + '}';
    }
}
