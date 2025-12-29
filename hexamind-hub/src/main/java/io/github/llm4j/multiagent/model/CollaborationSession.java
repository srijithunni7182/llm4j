package io.github.llm4j.multiagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a collaboration session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollaborationSession {

    private String sessionId;
    private String problem;
    private SessionStatus status;
    private Instant createdAt;
    private Instant completedAt;

    @Builder.Default
    private List<AgentThought> thoughts = new ArrayList<>();

    private Consensus consensus;
    private int currentRound;
    private int totalRounds;

    public enum SessionStatus {
        CREATED,
        ANALYZING,
        DEBATING,
        BUILDING_CONSENSUS,
        REFINING,
        COMPLETED,
        FAILED
    }

    public void addThought(AgentThought thought) {
        this.thoughts.add(thought);
    }
}
