package io.github.llm4j.privacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of PII detection in text.
 */
public class PIIDetectionResult {
    private final List<PIIEntity> entities;
    private final boolean containsPII;

    private PIIDetectionResult(Builder builder) {
        this.entities = Collections.unmodifiableList(new ArrayList<>(builder.entities));
        this.containsPII = !entities.isEmpty();
    }

    public List<PIIEntity> getEntities() {
        return entities;
    }

    public boolean containsPII() {
        return containsPII;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static PIIDetectionResult empty() {
        return builder().build();
    }

    public static class Builder {
        private List<PIIEntity> entities = new ArrayList<>();

        public Builder addEntity(PIIEntity entity) {
            this.entities.add(entity);
            return this;
        }

        public Builder entities(List<PIIEntity> entities) {
            this.entities = new ArrayList<>(entities);
            return this;
        }

        public PIIDetectionResult build() {
            return new PIIDetectionResult(this);
        }
    }

    @Override
    public String toString() {
        return "PIIDetectionResult{" +
                "containsPII=" + containsPII +
                ", entityCount=" + entities.size() +
                '}';
    }
}
