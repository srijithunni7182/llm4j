package io.github.llm4j.multiagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the consensus reached by agents.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Consensus {

    private String recommendation;
    private double agreementScore;

    @Builder.Default
    private Map<String, AgentOpinion> agentOpinions = new HashMap<>();

    @Builder.Default
    private List<String> keyPoints = new ArrayList<>();

    @Builder.Default
    private List<String> considerations = new ArrayList<>();

    private String reasoning;

    public String getRecommendation() {
        return recommendation;
    }

    public double getAgreementScore() {
        return agreementScore;
    }

    public Map<String, AgentOpinion> getAgentOpinions() {
        return agentOpinions;
    }

    public List<String> getKeyPoints() {
        return keyPoints;
    }

    public List<String> getConsiderations() {
        return considerations;
    }

    public String getReasoning() {
        return reasoning;
    }
}
