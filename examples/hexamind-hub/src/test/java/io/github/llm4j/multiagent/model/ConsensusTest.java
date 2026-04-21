package io.github.llm4j.multiagent.model;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ConsensusTest {

    @Test
    void testBuilderAndData() {
        Map<String, AgentOpinion> opinions = new HashMap<>();
        opinions.put("agent1", AgentOpinion.builder()
                .agentId("agent1")
                .recommendation("Do X")
                .build());

        List<String> keyPoints = Arrays.asList("Point 1", "Point 2");
        List<String> considerations = Arrays.asList("Consider A", "Consider B");

        Consensus consensus = Consensus.builder()
                .recommendation("Final recommendation")
                .agreementScore(0.85)
                .agentOpinions(opinions)
                .keyPoints(keyPoints)
                .considerations(considerations)
                .reasoning("Because of XYZ")
                .build();

        assertThat(consensus.getRecommendation()).isEqualTo("Final recommendation");
        assertThat(consensus.getAgreementScore()).isEqualTo(0.85);
        assertThat(consensus.getAgentOpinions()).hasSize(1);
        assertThat(consensus.getKeyPoints()).containsExactly("Point 1", "Point 2");
        assertThat(consensus.getConsiderations()).containsExactly("Consider A", "Consider B");
        assertThat(consensus.getReasoning()).isEqualTo("Because of XYZ");
    }

    @Test
    void testDefaultValues() {
        Consensus consensus = Consensus.builder()
                .recommendation("Test")
                .build();

        assertThat(consensus.getRecommendation()).isEqualTo("Test");
        assertThat(consensus.getAgentOpinions()).isNotNull();
        assertThat(consensus.getAgentOpinions()).isEmpty();
        assertThat(consensus.getKeyPoints()).isNotNull();
        assertThat(consensus.getKeyPoints()).isEmpty();
        assertThat(consensus.getConsiderations()).isNotNull();
        assertThat(consensus.getConsiderations()).isEmpty();
    }

    @Test
    void testNoArgsConstructor() {
        Consensus consensus = new Consensus();

        assertThat(consensus.getRecommendation()).isNull();
        assertThat(consensus.getAgreementScore()).isEqualTo(0.0);
        assertThat(consensus.getAgentOpinions()).isEmpty();
        assertThat(consensus.getKeyPoints()).isEmpty();
        assertThat(consensus.getConsiderations()).isEmpty();
        assertThat(consensus.getReasoning()).isNull();
    }

    @Test
    void testAllArgsConstructor() {
        Map<String, AgentOpinion> opinions = new HashMap<>();
        List<String> keyPoints = Collections.singletonList("Point");
        List<String> considerations = Collections.singletonList("Consider");

        Consensus consensus = new Consensus(
                "Recommendation",
                0.9,
                opinions,
                keyPoints,
                considerations,
                "Reasoning");

        assertThat(consensus.getRecommendation()).isEqualTo("Recommendation");
        assertThat(consensus.getAgreementScore()).isEqualTo(0.9);
        assertThat(consensus.getAgentOpinions()).isEqualTo(opinions);
        assertThat(consensus.getKeyPoints()).isEqualTo(keyPoints);
        assertThat(consensus.getConsiderations()).isEqualTo(considerations);
        assertThat(consensus.getReasoning()).isEqualTo("Reasoning");
    }

    @Test
    void testEmptyAgentOpinions() {
        Consensus consensus = Consensus.builder()
                .recommendation("No opinions")
                .agentOpinions(Collections.emptyMap())
                .build();

        assertThat(consensus.getAgentOpinions()).isEmpty();
    }

    @Test
    void testSingleAgentOpinion() {
        AgentOpinion opinion = AgentOpinion.builder()
                .agentId("solo")
                .recommendation("Solo recommendation")
                .confidence(0.95)
                .build();

        Map<String, AgentOpinion> opinions = new HashMap<>();
        opinions.put("solo", opinion);

        Consensus consensus = Consensus.builder()
                .recommendation("Based on solo agent")
                .agentOpinions(opinions)
                .agreementScore(1.0)
                .build();

        assertThat(consensus.getAgentOpinions()).hasSize(1);
        assertThat(consensus.getAgentOpinions().get("solo")).isEqualTo(opinion);
        assertThat(consensus.getAgreementScore()).isEqualTo(1.0);
    }

    @Test
    void testMultipleAgentOpinions() {
        Map<String, AgentOpinion> opinions = new HashMap<>();

        for (int i = 1; i <= 5; i++) {
            opinions.put("agent" + i, AgentOpinion.builder()
                    .agentId("agent" + i)
                    .recommendation("Recommendation " + i)
                    .confidence(0.8)
                    .build());
        }

        Consensus consensus = Consensus.builder()
                .recommendation("Multi-agent consensus")
                .agentOpinions(opinions)
                .agreementScore(0.75)
                .build();

        assertThat(consensus.getAgentOpinions()).hasSize(5);
        assertThat(consensus.getAgreementScore()).isEqualTo(0.75);
    }

    @Test
    void testConflictingOpinions() {
        Map<String, AgentOpinion> opinions = new HashMap<>();
        opinions.put("optimist", AgentOpinion.builder()
                .agentId("optimist")
                .recommendation("Go ahead")
                .confidence(0.9)
                .build());
        opinions.put("pessimist", AgentOpinion.builder()
                .agentId("pessimist")
                .recommendation("Don't do it")
                .confidence(0.8)
                .build());

        Consensus consensus = Consensus.builder()
                .recommendation("Proceed with caution")
                .agentOpinions(opinions)
                .agreementScore(0.3)
                .build();

        assertThat(consensus.getAgentOpinions()).hasSize(2);
        assertThat(consensus.getAgreementScore()).isLessThan(0.5);
    }

    @Test
    void testEmptyKeyPoints() {
        Consensus consensus = Consensus.builder()
                .recommendation("No key points")
                .keyPoints(Collections.emptyList())
                .build();

        assertThat(consensus.getKeyPoints()).isEmpty();
    }

    @Test
    void testMultipleKeyPoints() {
        List<String> keyPoints = Arrays.asList(
                "Point 1", "Point 2", "Point 3", "Point 4", "Point 5");

        Consensus consensus = Consensus.builder()
                .recommendation("With key points")
                .keyPoints(keyPoints)
                .build();

        assertThat(consensus.getKeyPoints()).hasSize(5);
        assertThat(consensus.getKeyPoints()).containsExactlyElementsOf(keyPoints);
    }

    @Test
    void testEmptyConsiderations() {
        Consensus consensus = Consensus.builder()
                .recommendation("No considerations")
                .considerations(Collections.emptyList())
                .build();

        assertThat(consensus.getConsiderations()).isEmpty();
    }

    @Test
    void testMultipleConsiderations() {
        List<String> considerations = Arrays.asList(
                "Risk A", "Risk B", "Opportunity C");

        Consensus consensus = Consensus.builder()
                .recommendation("With considerations")
                .considerations(considerations)
                .build();

        assertThat(consensus.getConsiderations()).hasSize(3);
        assertThat(consensus.getConsiderations()).containsExactlyElementsOf(considerations);
    }

    @Test
    void testAgreementScoreBounds() {
        Consensus lowAgreement = Consensus.builder()
                .recommendation("Low agreement")
                .agreementScore(0.0)
                .build();
        assertThat(lowAgreement.getAgreementScore()).isEqualTo(0.0);

        Consensus highAgreement = Consensus.builder()
                .recommendation("High agreement")
                .agreementScore(1.0)
                .build();
        assertThat(highAgreement.getAgreementScore()).isEqualTo(1.0);

        Consensus midAgreement = Consensus.builder()
                .recommendation("Mid agreement")
                .agreementScore(0.5)
                .build();
        assertThat(midAgreement.getAgreementScore()).isEqualTo(0.5);
    }

    @Test
    void testNullRecommendation() {
        Consensus consensus = Consensus.builder()
                .recommendation(null)
                .build();

        assertThat(consensus.getRecommendation()).isNull();
    }

    @Test
    void testEmptyRecommendation() {
        Consensus consensus = Consensus.builder()
                .recommendation("")
                .build();

        assertThat(consensus.getRecommendation()).isEmpty();
    }

    @Test
    void testLongRecommendation() {
        String longRec = "A".repeat(5000);
        Consensus consensus = Consensus.builder()
                .recommendation(longRec)
                .build();

        assertThat(consensus.getRecommendation()).hasSize(5000);
    }

    @Test
    void testNullReasoning() {
        Consensus consensus = Consensus.builder()
                .recommendation("Test")
                .reasoning(null)
                .build();

        assertThat(consensus.getReasoning()).isNull();
    }

    @Test
    void testDetailedReasoning() {
        String reasoning = "After careful analysis of all agent opinions, " +
                "considering the risks and opportunities, " +
                "we recommend proceeding with caution.";

        Consensus consensus = Consensus.builder()
                .recommendation("Proceed with caution")
                .reasoning(reasoning)
                .build();

        assertThat(consensus.getReasoning()).isEqualTo(reasoning);
    }

    @Test
    void testSettersAndGetters() {
        Consensus consensus = new Consensus();

        consensus.setRecommendation("New recommendation");
        consensus.setAgreementScore(0.88);

        Map<String, AgentOpinion> opinions = new HashMap<>();
        consensus.setAgentOpinions(opinions);

        List<String> keyPoints = Arrays.asList("K1", "K2");
        consensus.setKeyPoints(keyPoints);

        List<String> considerations = Arrays.asList("C1", "C2");
        consensus.setConsiderations(considerations);

        consensus.setReasoning("New reasoning");

        assertThat(consensus.getRecommendation()).isEqualTo("New recommendation");
        assertThat(consensus.getAgreementScore()).isEqualTo(0.88);
        assertThat(consensus.getAgentOpinions()).isEqualTo(opinions);
        assertThat(consensus.getKeyPoints()).isEqualTo(keyPoints);
        assertThat(consensus.getConsiderations()).isEqualTo(considerations);
        assertThat(consensus.getReasoning()).isEqualTo("New reasoning");
    }
}
