package io.github.llm4j.eval.criteria;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScoringTest {

    private static final EvalScenario SCENARIO =
            new EvalScenario("s1", "input", null, null, null, null, null);

    private static Criterion fixed(String name, double score, boolean passed) {
        return Criteria.judged(
                name,
                out -> new io.github.llm4j.eval.judge.JudgeVerdict(score, name + " says " + score),
                passed ? 0.0 : 1.01);
    }

    @Test
    void meanOfNonGuardrailCriteria() {
        Scorecard card =
                Scoring.score(
                        SCENARIO, "out", List.of(fixed("a", 1.0, true), fixed("b", 0.5, true)));
        assertThat(card.score()).isEqualTo(0.75);
        assertThat(card.guardrailViolated()).isFalse();
        assertThat(card.infrastructureFailure()).isFalse();
        assertThat(card.scenario()).isEqualTo("s1");
        assertThat(card.output()).isEqualTo("out");
        assertThat(card.outcomes()).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({
        "1.0, true, 1.0", // everything fine
        "1.0, false, 0.0", // guardrail failed: forced to zero however good the rest is
        "0.0, true, 0.0" // guardrail fine, judged score is what it is
    })
    void guardrailFailureForcesZero(double judgedScore, boolean guardPasses, double expected) {
        Criterion guard =
                Criteria.guardrail(
                        "no-pii",
                        out -> {
                            if (!guardPasses) {
                                throw new AssertionError("leaked an email");
                            }
                        });
        Scorecard card =
                Scoring.score(SCENARIO, "out", List.of(fixed("quality", judgedScore, true), guard));

        assertThat(card.score()).isEqualTo(expected);
        assertThat(card.guardrailViolated()).isEqualTo(!guardPasses);
        if (!guardPasses) {
            assertThat(card.feedback()).contains("[guardrail] no-pii: leaked an email");
        }
    }

    @Test
    void onlyGuardrails_scoreOneWhenTheyAllPass() {
        Scorecard card =
                Scoring.score(SCENARIO, "out", List.of(Criteria.guardrail("g", out -> {})));
        assertThat(card.score()).isEqualTo(1.0);
    }

    @Test
    void weightsAreHonoured() {
        Scorecard card =
                Scoring.score(
                        SCENARIO,
                        "out",
                        List.of(
                                Criteria.weighted(fixed("heavy", 1.0, true), 3.0),
                                fixed("light", 0.0, false)));
        assertThat(card.score()).isEqualTo(0.75);
    }

    @Test
    void zeroTotalWeightScoresOne() {
        Scorecard card =
                Scoring.score(
                        SCENARIO, "out", List.of(Criteria.weighted(fixed("a", 0.0, false), 0)));
        assertThat(card.score()).isEqualTo(1.0);
    }

    @Test
    void aThrowingCriterionIsRecordedAsAnErroredZeroNotAnAbort() {
        Criterion broken =
                Criteria.judged(
                        "judge",
                        out -> {
                            throw new IllegalStateException("judge down");
                        },
                        0.5);
        Scorecard card = Scoring.score(SCENARIO, "out", List.of(broken, fixed("ok", 1.0, true)));

        assertThat(card.infrastructureFailure()).isTrue();
        assertThat(card.score()).isEqualTo(0.5);
        assertThat(card.outcomes().get(0).error()).isTrue();
        assertThat(card.feedback()).contains("criterion \"judge\" errored: judge down");
    }

    @Test
    void feedbackListsOnlyFailuresDeduplicatedAndTruncated() {
        Criterion longWinded =
                Criteria.judged(
                        "long",
                        out -> new io.github.llm4j.eval.judge.JudgeVerdict(0.0, "x".repeat(1000)),
                        0.5);
        Scorecard card =
                Scoring.score(
                        SCENARIO,
                        "out",
                        List.of(longWinded, longWinded, fixed("fine", 1.0, true)),
                        50);

        assertThat(card.feedback()).startsWith("long: ").endsWith("...");
        assertThat(card.feedback().lines().count()).isEqualTo(1);
        assertThat(card.feedback()).doesNotContain("fine");
    }

    @Test
    void outputIsExtractedFromAgentResultsAndOtherObjects() {
        AgentResult result =
                AgentResult.builder()
                        .finalAnswer("the answer")
                        .completed(true)
                        .iterations(1)
                        .build();
        assertThat(Scoring.score(SCENARIO, result, List.of()).output()).isEqualTo("the answer");
        assertThat(Scoring.score(SCENARIO, 42, List.of()).output()).isEqualTo("42");
        assertThat(Scoring.score(SCENARIO, null, List.of()).output()).isEmpty();
    }

    @Test
    void systemFailureScorecardIsAnInfrastructureZero() {
        Scorecard card = Scoring.systemFailure(SCENARIO, new RuntimeException("agent crashed"));
        assertThat(card.score()).isZero();
        assertThat(card.infrastructureFailure()).isTrue();
        assertThat(card.feedback()).contains("agent crashed");
        assertThat(card.outcomes()).isEmpty();
    }
}
