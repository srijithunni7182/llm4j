package io.github.llm4j.audit;

import io.github.llm4j.agent.AgentResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an auditable event in the system.
 * Used for compliance tracking and explainability.
 */
public class AuditEvent {
    private final String sessionId;
    private final String userId;
    private final AgentResult agentResult;
    private final Instant timestamp;
    private final Map<String, Object> metadata;

    private AuditEvent(Builder builder) {
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId cannot be null");
        this.userId = builder.userId; // Can be null for anonymous sessions
        this.agentResult = builder.agentResult;
        this.timestamp = Objects.requireNonNull(builder.timestamp, "timestamp cannot be null");
        this.metadata = new HashMap<>(builder.metadata);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public AgentResult getAgentResult() {
        return agentResult;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private String userId;
        private AgentResult agentResult;
        private Instant timestamp = Instant.now();
        private Map<String, Object> metadata = new HashMap<>();

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder agentResult(AgentResult agentResult) {
            this.agentResult = agentResult;
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

        public AuditEvent build() {
            return new AuditEvent(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        AuditEvent that = (AuditEvent) o;
        return Objects.equals(sessionId, that.sessionId) &&
                Objects.equals(userId, that.userId) &&
                Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, userId, timestamp);
    }

    @Override
    public String toString() {
        return "AuditEvent{" +
                "sessionId='" + sessionId + '\'' +
                ", userId='" + userId + '\'' +
                ", timestamp=" + timestamp +
                ", metadata=" + metadata +
                '}';
    }
}
