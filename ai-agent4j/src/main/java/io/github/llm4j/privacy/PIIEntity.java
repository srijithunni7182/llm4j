package io.github.llm4j.privacy;

import java.util.Objects;

/** Represents a detected PII entity in text. */
public class PIIEntity {
    private final PIIType type;
    private final String value;
    private final int startIndex;
    private final int endIndex;

    private PIIEntity(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type cannot be null");
        this.value = Objects.requireNonNull(builder.value, "value cannot be null");
        this.startIndex = builder.startIndex;
        this.endIndex = builder.endIndex;

        if (startIndex < 0 || endIndex < startIndex) {
            throw new IllegalArgumentException(
                    "Invalid indices: start=" + startIndex + ", end=" + endIndex);
        }
    }

    public PIIType getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    public int getStartIndex() {
        return startIndex;
    }

    public int getEndIndex() {
        return endIndex;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private PIIType type;
        private String value;
        private int startIndex;
        private int endIndex;

        public Builder type(PIIType type) {
            this.type = type;
            return this;
        }

        public Builder value(String value) {
            this.value = value;
            return this;
        }

        public Builder startIndex(int startIndex) {
            this.startIndex = startIndex;
            return this;
        }

        public Builder endIndex(int endIndex) {
            this.endIndex = endIndex;
            return this;
        }

        public PIIEntity build() {
            return new PIIEntity(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PIIEntity piiEntity = (PIIEntity) o;
        return startIndex == piiEntity.startIndex
                && endIndex == piiEntity.endIndex
                && type == piiEntity.type
                && Objects.equals(value, piiEntity.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value, startIndex, endIndex);
    }

    @Override
    public String toString() {
        return "PIIEntity{"
                + "type="
                + type
                + ", value='"
                + value
                + '\''
                + ", startIndex="
                + startIndex
                + ", endIndex="
                + endIndex
                + '}';
    }
}
