package io.github.llm4j.multiagent.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a collaboration session.
 */
public class CollaborationSession {

    private String sessionId;
    private String problem;
    private SessionStatus status;
    private Instant createdAt;
    private Instant completedAt;

    private List<AgentThought> thoughts = new ArrayList<>();

    private Consensus consensus;
    private int currentRound;
    private int totalRounds;

    private Map<String, Integer> stats = new ConcurrentHashMap<>();

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

    public void incrementStat(String key) {
        stats.merge(key, 1, Integer::sum);
    }

    // Manual Constructor
    public CollaborationSession(String sessionId, String problem, SessionStatus status, Instant createdAt,
            Instant completedAt,
            List<AgentThought> thoughts, Consensus consensus, int currentRound, int totalRounds,
            Map<String, Integer> stats) {
        this.sessionId = sessionId;
        this.problem = problem;
        this.status = status;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.thoughts = thoughts;
        this.consensus = consensus;
        this.currentRound = currentRound;
        this.totalRounds = totalRounds;
        this.stats = stats;
    }

    public CollaborationSession() {
    }

    // Manual Getters
    public String getSessionId() {
        return sessionId;
    }

    public String getProblem() {
        return problem;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public List<AgentThought> getThoughts() {
        return thoughts;
    }

    public Consensus getConsensus() {
        return consensus;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public int getTotalRounds() {
        return totalRounds;
    }

    public Map<String, Integer> getStats() {
        return stats;
    }

    public void setContents(String sessionId, String problem) {
        this.sessionId = sessionId;
        this.problem = problem;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public void setCurrentRound(int round) {
        this.currentRound = round;
    }

    public void setTotalRounds(int rounds) {
        this.totalRounds = rounds;
    }

    public void setConsensus(Consensus consensus) {
        this.consensus = consensus;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setThoughts(List<AgentThought> thoughts) {
        this.thoughts = thoughts;
    }
}
