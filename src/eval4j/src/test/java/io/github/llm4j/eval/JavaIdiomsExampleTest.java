package io.github.llm4j.eval;

import static io.github.llm4j.eval.assertions.AgentAssertions.assertThat;
import static org.assertj.core.api.Assertions.allOf;
import static org.assertj.core.api.Assertions.anyOf;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.LlmJudgePresets;
import io.github.llm4j.eval.support.JudgeResponses;
import io.github.llm4j.eval.support.StubJudge;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The README's "feels like the Java you already write" examples, compiled and run so they can't
 * rot: plain JUnit 5 parameterized tests, AssertJ chaining, {@code allOf}/{@code anyOf} over judge
 * conditions, and {@code SoftAssertions} — nothing eval4j-specific to learn beyond the assertions.
 */
class JavaIdiomsExampleTest {

    private final LlmJudgePresets judge =
            LlmJudgePresets.using(StubJudge.always(JudgeResponses.rating(5, "fine")));

    private AgentResult agentAnswers(String answer) {
        return AgentResult.builder().finalAnswer(answer).completed(true).iterations(1).build();
    }

    static Stream<EvalScenario> scenarios() {
        return List.of(
                new EvalScenario("math", "What is 15% of 240?", "36", null, null, null, null),
                new EvalScenario("capital", "Capital of France?", "Paris", null, null, null, null))
                .stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void goldenScenarios_areJustParameterizedJUnitTests(EvalScenario scenario) {
        AgentResult result = agentAnswers("The answer is " + scenario.expectedOutputContains());

        assertThat(result)
                .completedSuccessfully()
                .hasFinalAnswerContaining(scenario.expectedOutputContains())
                .is(judge.answerRelevancy(scenario.input()));
    }

    @org.junit.jupiter.api.Test
    void judgeConditions_composeWithAssertJCombinators() {
        AgentResult result = agentAnswers("36");

        assertThat(result)
                .is(allOf(judge.correctness("36"), judge.answerRelevancy("What is 15% of 240?")))
                .is(anyOf(judge.hallucinationFree(List.of("15% of 240 is 36")), judge.toxicity()));
    }

    @org.junit.jupiter.api.Test
    void judgeConditions_workWithSoftAssertions() {
        AgentResult result = agentAnswers("36");

        SoftAssertions.assertSoftly(
                soft -> {
                    soft.assertThat((Object) result).is(judge.correctness("36"));
                    soft.assertThat((Object) result)
                            .is(judge.answerRelevancy("What is 15% of 240?"));
                });
    }
}
