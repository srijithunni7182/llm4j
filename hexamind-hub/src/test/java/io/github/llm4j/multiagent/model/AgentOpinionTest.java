package io.github.llm4j.multiagent.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOpinionTest {

        @Test
        void testBuilderAndData() {
                List<String> keyPoints = Arrays.asList("Point 1", "Point 2", "Point 3");

                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("agent1")
                                .agentName("Analyst")
                                .recommendation("Do X")
                                .confidence(0.95)
                                .keyPoints(keyPoints)
                                .build();

                assertThat(opinion.getAgentId()).isEqualTo("agent1");
                assertThat(opinion.getAgentName()).isEqualTo("Analyst");
                assertThat(opinion.getRecommendation()).isEqualTo("Do X");
                assertThat(opinion.getConfidence()).isEqualTo(0.95);
                assertThat(opinion.getKeyPoints()).containsExactly("Point 1", "Point 2", "Point 3");
        }

        @Test
        void testDefaultValues() {
                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("agent2")
                                .build();

                assertThat(opinion.getAgentId()).isEqualTo("agent2");
                assertThat(opinion.getKeyPoints()).isEmpty();
        }

        @Test
        void testNoArgsConstructor() {
                AgentOpinion opinion = new AgentOpinion();

                assertThat(opinion.getAgentId()).isNull();
                assertThat(opinion.getAgentName()).isNull();
                assertThat(opinion.getRecommendation()).isNull();
                assertThat(opinion.getConfidence()).isEqualTo(0.0);
                assertThat(opinion.getKeyPoints()).isEmpty();
        }

        @Test
        void testAllArgsConstructor() {
                List<String> keyPoints = Collections.singletonList("Point");
                List<String> concerns = Collections.singletonList("Concern");

                AgentOpinion opinion = new AgentOpinion(
                                "id", "name", "recommendation", 0.8, keyPoints, concerns);

                assertThat(opinion.getAgentId()).isEqualTo("id");
                assertThat(opinion.getAgentName()).isEqualTo("name");
                assertThat(opinion.getRecommendation()).isEqualTo("recommendation");
                assertThat(opinion.getConfidence()).isEqualTo(0.8);
                assertThat(opinion.getKeyPoints()).isEqualTo(keyPoints);
                assertThat(opinion.getConcerns()).isEqualTo(concerns);
        }

        @Test
        void testConfidenceBounds() {
                AgentOpinion lowConfidence = AgentOpinion.builder()
                                .agentId("low")
                                .confidence(0.0)
                                .build();
                assertThat(lowConfidence.getConfidence()).isEqualTo(0.0);

                AgentOpinion highConfidence = AgentOpinion.builder()
                                .agentId("high")
                                .confidence(1.0)
                                .build();
                assertThat(highConfidence.getConfidence()).isEqualTo(1.0);

                AgentOpinion midConfidence = AgentOpinion.builder()
                                .agentId("mid")
                                .confidence(0.5)
                                .build();
                assertThat(midConfidence.getConfidence()).isEqualTo(0.5);
        }

        @Test
        void testVeryLowConfidence() {
                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("uncertain")
                                .recommendation("Not sure")
                                .confidence(0.1)
                                .build();

                assertThat(opinion.getConfidence()).isLessThan(0.2);
        }

        @Test
        void testVeryHighConfidence() {
                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("certain")
                                .recommendation("Definitely do this")
                                .confidence(0.99)
                                .build();

                assertThat(opinion.getConfidence()).isGreaterThan(0.9);
        }

        @Test
        void testEmptyKeyPoints() {
                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("empty")
                                .keyPoints(Collections.emptyList())
                                .build();

                assertThat(opinion.getKeyPoints()).isEmpty();
        }

        @Test
        void testSingleKeyPoint() {
                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("single")
                                .keyPoints(Collections.singletonList("Only point"))
                                .build();

                assertThat(opinion.getKeyPoints()).hasSize(1);
                assertThat(opinion.getKeyPoints().get(0)).isEqualTo("Only point");
        }

        @Test
        void testMultipleKeyPoints() {
                List<String> keyPoints = Arrays.asList(
                                "First point",
                                "Second point",
                                "Third point",
                                "Fourth point",
                                "Fifth point");

                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("multiple")
                                .keyPoints(keyPoints)
                                .build();

                assertThat(opinion.getKeyPoints()).hasSize(5);
                assertThat(opinion.getKeyPoints()).containsExactlyElementsOf(keyPoints);
        }

        @Test
        void testNullRecommendation() {
                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("null-rec")
                                .recommendation(null)
                                .build();

                assertThat(opinion.getRecommendation()).isNull();
        }

        @Test
        void testEmptyRecommendation() {
                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("empty-rec")
                                .recommendation("")
                                .build();

                assertThat(opinion.getRecommendation()).isEmpty();
        }

        @Test
        void testLongRecommendation() {
                String longRec = "This is a very detailed recommendation that goes on and on. ".repeat(100);

                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("long-rec")
                                .recommendation(longRec)
                                .build();

                assertThat(opinion.getRecommendation()).hasSize(longRec.length());
        }

        @Test
        void testNullAgentId() {
                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId(null)
                                .recommendation("Test")
                                .build();

                assertThat(opinion.getAgentId()).isNull();
        }

        @Test
        void testNullAgentName() {
                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("id")
                                .agentName(null)
                                .build();

                assertThat(opinion.getAgentName()).isNull();
        }

        @Test
        void testEmptyAgentName() {
                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("id")
                                .agentName("")
                                .build();

                assertThat(opinion.getAgentName()).isEmpty();
        }

        @Test
        void testCompleteOpinion() {
                List<String> keyPoints = Arrays.asList(
                                "Strong market demand",
                                "Technical feasibility confirmed",
                                "Budget within limits");

                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("business-analyst")
                                .agentName("Business Analyst")
                                .recommendation("Proceed with the project implementation")
                                .confidence(0.87)
                                .keyPoints(keyPoints)
                                .build();

                assertThat(opinion.getAgentId()).isEqualTo("business-analyst");
                assertThat(opinion.getAgentName()).isEqualTo("Business Analyst");
                assertThat(opinion.getRecommendation()).isEqualTo("Proceed with the project implementation");
                assertThat(opinion.getConfidence()).isEqualTo(0.87);
                assertThat(opinion.getKeyPoints()).hasSize(3);
                assertThat(opinion.getKeyPoints()).contains("Strong market demand");
        }

        @Test
        void testNegativeOpinion() {
                List<String> keyPoints = Arrays.asList(
                                "High risk",
                                "Insufficient resources",
                                "Market uncertainty");

                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("risk-analyst")
                                .agentName("Risk Analyst")
                                .recommendation("Do not proceed")
                                .confidence(0.92)
                                .keyPoints(keyPoints)
                                .build();

                assertThat(opinion.getRecommendation()).contains("not proceed");
                assertThat(opinion.getConfidence()).isGreaterThan(0.9);
                assertThat(opinion.getKeyPoints()).contains("High risk");
        }

        @Test
        void testSettersAndGetters() {
                AgentOpinion opinion = new AgentOpinion();

                opinion.setAgentId("new-id");
                opinion.setAgentName("New Name");
                opinion.setRecommendation("New recommendation");
                opinion.setConfidence(0.75);

                List<String> keyPoints = Arrays.asList("K1", "K2");
                opinion.setKeyPoints(keyPoints);

                assertThat(opinion.getAgentId()).isEqualTo("new-id");
                assertThat(opinion.getAgentName()).isEqualTo("New Name");
                assertThat(opinion.getRecommendation()).isEqualTo("New recommendation");
                assertThat(opinion.getConfidence()).isEqualTo(0.75);
                assertThat(opinion.getKeyPoints()).isEqualTo(keyPoints);
        }

        @Test
        void testKeyPointsWithSpecialCharacters() {
                List<String> keyPoints = Arrays.asList(
                                "Point with \"quotes\"",
                                "Point with 'apostrophes'",
                                "Point with \n newlines",
                                "Point with \t tabs");

                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("special")
                                .keyPoints(keyPoints)
                                .build();

                assertThat(opinion.getKeyPoints()).hasSize(4);
                assertThat(opinion.getKeyPoints().get(0)).contains("quotes");
        }
}
