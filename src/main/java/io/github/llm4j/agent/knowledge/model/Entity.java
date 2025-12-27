package io.github.llm4j.agent.knowledge.model;

import java.util.*;

/**
 * Represents an entity in a knowledge graph.
 */
public class Entity {

    private final String id;
    private final String type;
    private final Map<String, Object> properties;

    private Entity(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id cannot be null");
        this.type = Objects.requireNonNull(builder.type, "type cannot be null");
        this.properties = Collections.unmodifiableMap(new HashMap<>(builder.properties));
    }

    public String getId() {
        return id;
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
        private String id;
        private String type;
        private Map<String, Object> properties = new HashMap<>();

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
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

        public Entity build() {
            return new Entity(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Entity entity = (Entity) o;
        return Objects.equals(id, entity.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Entity{" +
                "id='" + id + '\'' +
                ", type='" + type + '\'' +
                ", properties=" + properties.size() +
                '}';
    }
}
