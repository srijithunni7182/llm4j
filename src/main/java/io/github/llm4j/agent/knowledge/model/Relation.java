package io.github.llm4j.agent.knowledge.model;

import java.util.*;

/**
 * Represents a relationship between entities in a knowledge graph.
 */
public class Relation {

    private final String type;
    private final Map<String, Object> properties;

    private Relation(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type cannot be null");
        this.properties = Collections.unmodifiableMap(new HashMap<>(builder.properties));
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public Object getProperty(String key) {
        return properties.get(key);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String type;
        private Map<String, Object> properties = new HashMap<>();

        private Builder() {
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder properties(Map<String, Object> properties) {
            this.properties.putAll(properties);
            return this;
        }

        public Builder addProperty(String key, Object value) {
            this.properties.put(key, value);
            return this;
        }

        public Relation build() {
            return new Relation(this);
        }
    }

    @Override
    public String toString() {
        return "Relation{" +
                "type='" + type + '\'' +
                ", properties=" + properties.size() +
                '}';
    }
}
