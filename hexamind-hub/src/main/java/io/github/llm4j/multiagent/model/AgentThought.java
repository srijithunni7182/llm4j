package io.github.llm4j.multiagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a thought from an AI agent during collaboration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentThought {

    private String id;
    private String agentId;
    private String agentName;
    private String content;
    private ThoughtType type;
    private Instant timestamp;

    @Builder.Default
    private List<String> referencesTo = new ArrayList<>();

    private double confidence;

    public String getId() {
        return id;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getContent() {
        return content;
    }

    public ThoughtType getType() {
        return type;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public double getConfidence() {
        return confidence;
    }

    public enum ThoughtType {
        ANALYSIS, // Initial analysis of the problem
        ARGUMENT, // Presenting an argument
        COUNTER_ARGUMENT, // Responding to another agent
        CRITIQUE, // Specifically critiquing others (Rahul's forte)
        REBUTTAL, // Responding to critiques
        AGREEMENT, // Agreeing with another agent
        REFINEMENT, // Refining previous thoughts
        CONCLUSION // Final conclusion
    }
}
