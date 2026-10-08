package io.github.llm4j.eval.assertions;

import static io.github.llm4j.eval.assertions.ConversationAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.AgentResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationAssertTest {

    private static AgentResult turn(String finalAnswer, boolean completed) {
        return AgentResult.builder()
                .finalAnswer(finalAnswer)
                .completed(completed)
                .iterations(1)
                .build();
    }

    @Test
    void hasTurnCount_passesOnMatchingSize() {
        List<AgentResult> turns = List.of(turn("hi", true), turn("bye", true));
        assertThat(turns).hasTurnCount(2);
    }

    @Test
    void hasTurnCount_failsOnMismatchedSize() {
        List<AgentResult> turns = List.of(turn("hi", true));
        assertThatThrownBy(() -> assertThat(turns).hasTurnCount(2))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void allCompletedSuccessfully_failsWhenAnyTurnIncomplete() {
        List<AgentResult> turns = List.of(turn("hi", true), turn("stuck", false));
        assertThatThrownBy(() -> assertThat(turns).allCompletedSuccessfully())
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void allCompletedSuccessfully_passesWhenEveryTurnCompleted() {
        List<AgentResult> turns = List.of(turn("hi", true), turn("bye", true));
        assertThat(turns).allCompletedSuccessfully();
    }

    @Test
    void turn_returnsAssertableResultForGivenIndex() {
        List<AgentResult> turns = List.of(turn("hi", true), turn("The answer is 36.", true));
        assertThat(turns).turn(1).hasFinalAnswerContaining("36");
    }

    @Test
    void turn_failsForOutOfRangeIndex() {
        List<AgentResult> turns = List.of(turn("hi", true));
        assertThatThrownBy(() -> assertThat(turns).turn(5)).isInstanceOf(AssertionError.class);
    }
}
