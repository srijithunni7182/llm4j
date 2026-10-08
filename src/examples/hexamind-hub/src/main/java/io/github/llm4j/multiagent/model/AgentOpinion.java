package io.github.llm4j.multiagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an agent's opinion on the problem.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentOpinion {

    private String agentId;
    private String agentName;
    private String recommendation;
    private double confidence;

    @Builder.Default
    private List<String> keyPoints = new ArrayList<>();

    @Builder.Default
    private List<String> concerns = new ArrayList<>();

    public String getRecommendation() {
        return recommendation;
    }

    public double getConfidence() {
        return confidence;
    }

    public List<String> getKeyPoints() {
        return keyPoints;
    }
}
