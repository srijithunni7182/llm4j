package io.github.llm4j.eval.assertions;

import static io.github.llm4j.eval.assertions.AgentAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.model.ConfidenceScore;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AgentResultAssertTest {

    private static AgentResult resultWithSteps(String finalAnswer, String... toolNames) {
        AgentResult.Builder builder =
                AgentResult.builder()
                        .finalAnswer(finalAnswer)
                        .completed(true)
                        .iterations(toolNames.length + 1)
                        .confidence(ConfidenceScore.high("clean run"));
        for (String toolName : toolNames) {
            builder.addStep(new AgentResult.AgentStep("thinking", toolName, "{}", "ok"));
        }
        return builder.build();
    }

    @Test
    void hasFinalAnswerContaining_passesOnSubstringMatch() {
        AgentResult result = resultWithSteps("The answer is 36.");
        assertThat(result).hasFinalAnswerContaining("36");
    }

    @Test
    void hasFinalAnswerContaining_failsWhenSubstringMissing() {
        AgentResult result = resultWithSteps("The answer is 36.");
        assertThatThrownBy(() -> assertThat(result).hasFinalAnswerContaining("42"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void hasFinalAnswerMatching_passesOnRegexMatch() {
        AgentResult result = resultWithSteps("The answer is 36.");
        assertThat(result).hasFinalAnswerMatching(Pattern.compile("\\d+"));
    }

    @Test
    void hasFinalAnswerMatching_failsWhenNoMatch() {
        AgentResult result = resultWithSteps("no numbers here");
        assertThatThrownBy(
                        () -> assertThat(result).hasFinalAnswerMatching(Pattern.compile("\\d+")))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void usesTool_isCaseInsensitive() {
        AgentResult result = resultWithSteps("36", "Calculator");
        assertThat(result).usesTool("calculator");
    }

    @Test
    void usesTool_failsWhenToolNotUsed() {
        AgentResult result = resultWithSteps("36", "Calculator");
        assertThatThrownBy(() -> assertThat(result).usesTool("web_search"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void usesToolsExactly_passesOnExactOrderedMatch() {
        AgentResult result = resultWithSteps("done", "search", "calculator");
        assertThat(result).usesToolsExactly("search", "calculator");
    }

    @Test
    void usesToolsExactly_failsOnWrongOrder() {
        AgentResult result = resultWithSteps("done", "search", "calculator");
        assertThatThrownBy(
                        () -> assertThat(result).usesToolsExactly("calculator", "search"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void usesToolsExactly_failsOnExtraTool() {
        AgentResult result = resultWithSteps("done", "search", "calculator");
        assertThatThrownBy(() -> assertThat(result).usesToolsExactly("search"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void usesToolsInOrder_passesWhenSubsequenceMatchesWithExtraStepsBetween() {
        AgentResult result = resultWithSteps("done", "search", "calculator", "email");
        assertThat(result).usesToolsInOrder("search", "email");
    }

    @Test
    void usesToolsInOrder_failsWhenOrderIsWrong() {
        AgentResult result = resultWithSteps("done", "search", "calculator", "email");
        assertThatThrownBy(() -> assertThat(result).usesToolsInOrder("email", "search"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void usesToolsInOrder_failsWhenAToolIsMissingEntirely() {
        AgentResult result = resultWithSteps("done", "search", "calculator");
        assertThatThrownBy(() -> assertThat(result).usesToolsInOrder("search", "email"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void usesNoTools_passesWhenNoStepsRecorded() {
        AgentResult result = resultWithSteps("hello");
        assertThat(result).usesNoTools();
    }

    @Test
    void usesNoTools_failsWhenAToolWasUsed() {
        AgentResult result = resultWithSteps("36", "calculator");
        assertThatThrownBy(() -> assertThat(result).usesNoTools())
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void isConfidentAbove_passesWhenScoreExceedsThreshold() {
        AgentResult result =
                AgentResult.builder()
                        .finalAnswer("36")
                        .completed(true)
                        .iterations(1)
                        .confidence(ConfidenceScore.high("clean run"))
                        .build();
        assertThat(result).isConfidentAbove(0.5);
    }

    @Test
    void isConfidentAbove_failsWhenScoreAtOrBelowThreshold() {
        AgentResult result =
                AgentResult.builder()
                        .finalAnswer("36")
                        .completed(true)
                        .iterations(1)
                        .confidence(ConfidenceScore.low("uncertain"))
                        .build();
        assertThatThrownBy(() -> assertThat(result).isConfidentAbove(0.5))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void completedSuccessfully_failsWhenAgentDidNotComplete() {
        AgentResult result =
                AgentResult.builder()
                        .finalAnswer("Maximum iterations reached.")
                        .completed(false)
                        .iterations(10)
                        .uncertaintyDetected(true)
                        .uncertaintyReason("max iterations")
                        .build();
        assertThatThrownBy(() -> assertThat(result).completedSuccessfully())
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void completesWithinIterations_passesWhenUnderLimit() {
        AgentResult result = resultWithSteps("36", "calculator");
        assertThat(result).completesWithinIterations(5);
    }

    @Test
    void completesWithinIterations_failsWhenOverLimit() {
        AgentResult result =
                AgentResult.builder().finalAnswer("36").completed(true).iterations(9).build();
        assertThatThrownBy(() -> assertThat(result).completesWithinIterations(5))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void hasValidJson_passesForWellFormedJson() {
        AgentResult result =
                AgentResult.builder()
                        .finalAnswer("{\"answer\":36}")
                        .completed(true)
                        .iterations(1)
                        .build();
        assertThat(result).hasValidJson(java.util.Map.class);
    }

    @Test
    void hasValidJson_failsForMalformedJson() {
        AgentResult result =
                AgentResult.builder()
                        .finalAnswer("not json")
                        .completed(true)
                        .iterations(1)
                        .build();
        assertThatThrownBy(() -> assertThat(result).hasValidJson(java.util.Map.class))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void chainsMultipleAssertionsFluently() {
        AgentResult result = resultWithSteps("The answer is 36.", "calculator");
        assertThat(result)
                .completedSuccessfully()
                .usesToolsExactly("calculator")
                .hasFinalAnswerContaining("36")
                .isConfidentAbove(0.5);
    }

    @Test
    void assertThat_failsOnNullActual() {
        assertThatThrownBy(() -> assertThat((AgentResult) null).completedSuccessfully())
                .isInstanceOf(AssertionError.class);
    }
}
