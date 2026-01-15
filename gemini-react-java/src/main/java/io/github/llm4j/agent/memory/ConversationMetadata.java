package io.github.llm4j.agent.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Metadata for a conversation session.
 */
public class ConversationMetadata {
    private final String sessionId;
    private final String summary;
    private final Instant lastUpdated;

    @JsonCreator
    public ConversationMetadata(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("summary") String summary,
            @JsonProperty("lastUpdated") Instant lastUpdated) {
        this.sessionId = sessionId;
        this.summary = summary;
        this.lastUpdated = lastUpdated;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }
}
