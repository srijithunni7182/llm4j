package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.JudgeVerdict;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Test;

/**
 * F23: one judge sample per line was read as a measurement, but a move of a point between runs is
 * noise. With several samples the line gets the median, and samples that disagree by a wide margin
 * leave the line unjudged instead of deciding it on a coin toss.
 */
class JudgeSamplesTest {
    private static final EvalScenario S =
            new EvalScenario("n", "q", null, null, null, null, null, "id", null, null, null, null);

    private static RubricJudge scripted(double... scores) {
        Deque<Double> queue = new ArrayDeque<>();
        for (double s : scores) queue.add(s);
        return (criteria, subject, scenario) -> new JudgeVerdict(queue.removeFirst(), "sample " + queue.size());
    }

    @Test
    void oneSampleIsPassedThroughUntouched() {
        JudgeVerdict v = RubricJudge.sampled(scripted(0.8), 1).judge("c", "s", S);

        assertThat(v.score()).isEqualTo(0.8);
    }

    @Test
    void severalSamplesGiveTheMedianAndTheSamplesInTheReason() {
        JudgeVerdict v = RubricJudge.sampled(scripted(0.9, 0.7, 0.8), 3).judge("c", "s", S);

        assertThat(v.score()).isEqualTo(0.8);
        assertThat(v.reason()).contains("median of 3").contains("0.90").contains("0.70").contains("0.80");
    }

    @Test
    void samplesThatDisagreeWidelyAreUnstableAndNotDecided() {
        assertThatThrownBy(() -> RubricJudge.sampled(scripted(0.9, 0.3, 0.8), 3).judge("c", "s", S))
                .isInstanceOf(RubricJudge.UnstableJudgement.class)
                .hasMessageContaining("unstable")
                .hasMessageContaining("0.30")
                .hasMessageContaining("0.90");
    }

    @Test
    void samplesThatDisagreeJustUnderTheLimitAreStillDecided() {
        JudgeVerdict v = RubricJudge.sampled(scripted(0.9, 0.55), 2).judge("c", "s", S);

        // the median of two is their mean
        assertThat(v.score()).isCloseTo(0.725, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void theNumberOfSamplesIsBounded() {
        assertThatThrownBy(() -> RubricJudge.sampled(scripted(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RubricJudge.sampled(scripted(), RubricJudge.MAX_SAMPLES + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(RubricJudge.MAX_SAMPLES));
    }

    @Test
    void anUnstableLineIsUnjudgedInTheRunnerNotPassedOrFailed() {
        io.github.llm4j.loom.eval.EvalTestSupport.Models models =
                new io.github.llm4j.loom.eval.EvalTestSupport.Models(
                        (system, n) -> EvalTestSupport.finalAnswer("an answer"));
        EvalScenario scenario =
                new EvalScenario("n", "q", null, null, null, null, null, "a-1", null, null,
                        java.util.List.of("Is whole"), null);
        EvalRunner runner =
                new EvalRunner(
                        before ->
                                EvalTestSupport.executor(
                                        "agent Helper { model: \"m\" system: \"s\" }\nworkflow Main(t) { delegate \"{t}\" to Helper -> r }\n",
                                        models,
                                        new io.github.llm4j.loom.execution.ToolRegistry(),
                                        before),
                        model -> RubricJudge.sampled(scripted(0.9, 0.2, 0.8), 3),
                        name -> "m");

        ScenarioResult r = runner.agent("f", "Helper", scenario);

        assertThat(r.checks().get(0).status()).isEqualTo(Status.UNJUDGED);
        assertThat(r.checks().get(0).detail()).contains("unstable");
    }
}
