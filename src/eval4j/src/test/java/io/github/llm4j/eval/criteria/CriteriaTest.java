package io.github.llm4j.eval.criteria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.ConversationJudgePresets;
import io.github.llm4j.eval.judge.JudgeVerdict;
import io.github.llm4j.eval.judge.LlmJudgePresets;
import io.github.llm4j.eval.judge.Transcript;
import io.github.llm4j.eval.support.JudgeResponses;
import io.github.llm4j.eval.support.StubJudge;
import java.util.List;
import org.junit.jupiter.api.Test;

class CriteriaTest {

    private static final EvalScenario SCENARIO =
            new EvalScenario("s1", "What is 15% of 240?", null, "36", null, null, null);

    @Test
    void judgedLlmCondition_returnsScoreReasonAndThresholdBasedPass() {
        var presets = LlmJudgePresets.using(StubJudge.always(JudgeResponses.rating(4, "close")));
        Criterion strict = Criteria.judged(presets.correctness("36", 0.9));
        Criterion lenient = Criteria.judged(presets.correctness("36", 0.5));

        CriterionResult failing = strict.score(SCENARIO, "35");
        CriterionResult passing = lenient.score(SCENARIO, "35");

        assertThat(failing.score()).isEqualTo(0.75);
        assertThat(failing.passed()).isFalse();
        assertThat(failing.feedback()).contains("close");
        assertThat(passing.passed()).isTrue();
        assertThat(strict.name()).isEqualTo("Correctness");
    }

    @Test
    void judgedRagCondition_usesTheMetricNameAndIgnoresTheOutput() {
        var presets = LlmJudgePresets.using(StubJudge.always(JudgeResponses.rating(5, "relevant")));
        Criterion criterion =
                Criteria.judged(
                        presets.contextualRelevancy("q", List.of("chunk one", "chunk two")));

        CriterionResult result = criterion.score(SCENARIO, "anything");

        assertThat(criterion.name()).isEqualTo("Contextual Relevancy");
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void judgedConversationCondition_mapsTheOutputToATranscript() {
        var conv = ConversationJudgePresets.using(StubJudge.always(JudgeResponses.rating(5, "ok")));
        Criterion criterion =
                Criteria.judged(
                        conv.conversationRelevancy(),
                        out ->
                                Transcript.builder()
                                        .user("hi")
                                        .assistant(String.valueOf(out))
                                        .build());

        assertThat(criterion.score(SCENARIO, "hello").score()).isEqualTo(1.0);
        assertThat(criterion.name()).isEqualTo("Conversation Relevancy");
    }

    @Test
    void judgedFunction_passesAtOrAboveTheThreshold() {
        Criterion criterion = Criteria.judged("custom", out -> new JudgeVerdict(0.6, "so-so"), 0.6);
        assertThat(criterion.score(SCENARIO, "x").passed()).isTrue();
        assertThat(
                        Criteria.judged("custom", out -> new JudgeVerdict(0.59, "so-so"), 0.6)
                                .score(SCENARIO, "x")
                                .passed())
                .isFalse();
    }

    @Test
    void assertion_passingScoresOneAndFailingCarriesTheMessage() {
        Criterion criterion =
                Criteria.assertion(
                        "contains-36",
                        out -> {
                            if (!String.valueOf(out).contains("36")) {
                                throw new AssertionError("expected 36 in the answer");
                            }
                        });

        assertThat(criterion.score(SCENARIO, "it is 36").score()).isEqualTo(1.0);
        CriterionResult failed = criterion.score(SCENARIO, "it is 40");
        assertThat(failed.score()).isZero();
        assertThat(failed.passed()).isFalse();
        assertThat(failed.feedback()).isEqualTo("expected 36 in the answer");
    }

    @Test
    void assertion_otherExceptionsPropagateSoScoringCanFlagThemAsErrors() {
        Criterion criterion =
                Criteria.assertion(
                        "boom",
                        out -> {
                            throw new IllegalStateException("bug in the check");
                        });
        assertThatThrownBy(() -> criterion.score(SCENARIO, "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scenarioAssertion_receivesTheScenario() {
        Criterion criterion =
                Criteria.scenarioAssertion(
                        "matches-expected",
                        (scenario, out) -> {
                            if (!scenario.expectedOutput().equals(out)) {
                                throw new AssertionError("wanted " + scenario.expectedOutput());
                            }
                        });
        assertThat(criterion.score(SCENARIO, "36").passed()).isTrue();
        assertThat(criterion.score(SCENARIO, "37").feedback()).isEqualTo("wanted 36");
    }

    @Test
    void guardrailAndWeightedWrappersChangeOnlyTheirOwnAttribute() {
        Criterion base = Criteria.assertion("a", out -> {});
        Criterion guard = Criteria.guardrail(base);
        Criterion heavy = Criteria.weighted(base, 3.0);

        assertThat(base.isGuardrail()).isFalse();
        assertThat(guard.isGuardrail()).isTrue();
        assertThat(guard.name()).isEqualTo("a");
        assertThat(heavy.isGuardrail()).isFalse();
        assertThat(heavy.weight()).isEqualTo(3.0);
        assertThat(Criteria.guardrail("g", out -> {}).isGuardrail()).isTrue();
        assertThat(Criteria.weighted(guard, 2.0).isGuardrail()).isTrue();
        assertThatThrownBy(() -> Criteria.weighted(base, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void perScenario_buildsTheCriterionFromEachScenario() {
        Criterion criterion =
                Criteria.perScenario(
                        "exact",
                        s ->
                                Criteria.assertion(
                                        "exact",
                                        out -> {
                                            if (!s.expectedOutput().equals(out)) {
                                                throw new AssertionError(
                                                        "not " + s.expectedOutput());
                                            }
                                        }));
        assertThat(criterion.score(SCENARIO, "36").passed()).isTrue();
        assertThat(criterion.score(SCENARIO, "1").passed()).isFalse();
    }

    @Test
    void factoriesRejectNulls() {
        assertThatThrownBy(
                        () -> Criteria.judged((io.github.llm4j.eval.judge.LlmJudgeCondition) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Criteria.assertion(null, out -> {}))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Criteria.guardrail((Criterion) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void criterionResult_validatesTheScoreRange() {
        assertThatThrownBy(() -> new CriterionResult(1.1, true, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CriterionResult(Double.NaN, true, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CriterionResult(0.5, false, null).feedback()).isEmpty();
    }
}
