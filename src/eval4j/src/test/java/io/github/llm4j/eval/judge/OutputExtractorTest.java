package io.github.llm4j.eval.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.model.LLMResponse;
import org.junit.jupiter.api.Test;

class OutputExtractorTest {

    @Test
    void extract_readsFinalAnswerFromAgentResult() {
        AgentResult result = AgentResult.builder().finalAnswer("36").completed(true).build();
        assertThat(OutputExtractor.extract(result)).isEqualTo("36");
    }

    @Test
    void extract_readsContentFromLlmResponse() {
        LLMResponse response = LLMResponse.builder().content("36").build();
        assertThat(OutputExtractor.extract(response)).isEqualTo("36");
    }

    @Test
    void extract_returnsStringAsIs() {
        assertThat(OutputExtractor.extract("36")).isEqualTo("36");
    }

    @Test
    void extract_throwsForUnsupportedType() {
        assertThatThrownBy(() -> OutputExtractor.extract(42))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extract_throwsForNull() {
        assertThatThrownBy(() -> OutputExtractor.extract(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void extractTrajectory_returnsNullForNonAgentResult() {
        assertThat(OutputExtractor.extractTrajectory("plain string")).isNull();
        assertThat(OutputExtractor.extractTrajectory(LLMResponse.builder().content("x").build()))
                .isNull();
    }

    @Test
    void extractTrajectory_returnsNullWhenNoSteps() {
        AgentResult result = AgentResult.builder().finalAnswer("36").completed(true).build();
        assertThat(OutputExtractor.extractTrajectory(result)).isNull();
    }

    @Test
    void extractTrajectory_rendersEachStep() {
        AgentResult result =
                AgentResult.builder()
                        .finalAnswer("36")
                        .completed(true)
                        .addStep(
                                new AgentResult.AgentStep(
                                        "need to calculate",
                                        "calculator",
                                        "{\"expression\":\"15%*240\"}",
                                        "36",
                                        AgentResult.StepOutcome.EXECUTED))
                        .build();

        String trajectory = OutputExtractor.extractTrajectory(result);

        assertThat(trajectory)
                .contains("Step 1:")
                .contains("need to calculate")
                .contains("calculator")
                .contains("15%*240")
                .contains("EXECUTED")
                .contains("Observation: 36");
    }
}
