package io.github.llm4j.fairness;

import java.util.HashMap;
import java.util.Map;

/** Context information for bias detection. Helps bias detectors make more informed decisions. */
public class BiasContext {
    private final String sessionId;
    private final String userId;
    private final String taskType;
    private final Map<String, Object> additionalContext;

    private BiasContext(Builder builder) {
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        this.taskType = builder.taskType;
        this.additionalContext = new HashMap<>(builder.additionalContext);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getTaskType() {
        return taskType;
    }

    public Map<String, Object> getAdditionalContext() {
        return new HashMap<>(additionalContext);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static BiasContext empty() {
        return builder().build();
    }

    public static class Builder {
        private String sessionId;
        private String userId;
        private String taskType;
        private Map<String, Object> additionalContext = new HashMap<>();

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder taskType(String taskType) {
            this.taskType = taskType;
            return this;
        }

        public Builder additionalContext(Map<String, Object> context) {
            this.additionalContext = new HashMap<>(context);
            return this;
        }

        public Builder addContext(String key, Object value) {
            this.additionalContext.put(key, value);
            return this;
        }

        public BiasContext build() {
            return new BiasContext(this);
        }
    }
}
