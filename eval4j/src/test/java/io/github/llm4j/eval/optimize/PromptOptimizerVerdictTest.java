package io.github.llm4j.eval.optimize;

import static io.github.llm4j.eval.optimize.SimulationSupport.PARAM;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The verdict's noise handling: tolerance scales with split size; target needs test agreement. */
class PromptOptimizerVerdictTest {

    private static EvalScenario scenario(String name) {
        return new EvalScenario(name, name + " question", null, null, null, null, null);
    }

    /** {@code perCategory} of each learnable category, plus {@code hidden} unlearnable ones. */
    private static List<EvalScenario> mixed(String prefix, int perCategory, int hidden) {
        List<EvalScenario> out = new ArrayList<>();
        for (String category : SimulationSupport.CATEGORIES) {
            for (int i = 1; i <= perCategory; i++) {
                out.add(scenario(category + "-" + prefix + i));
            }
        }
        for (int i = 1; i <= hidden; i++) {
            out.add(scenario("hidden-" + prefix + i));
        }
        return out;
    }

    private static PromptOptimizer.Builder base(Split split) {
        return SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                .scenarios(List.of())
                .split(split);
    }

    @Test
    void aSmallTestSplit_doesNotTurnAGapWithinNoiseIntoAnOverfittingVerdict() {
        // validation 20 (all learnable) vs test 12 (10 learnable + 2 hidden): test 0.867, gap
        // 0.133.
        // That is above the 0.10 floor but inside one standard error (0.19) of splits this small.
        List<EvalScenario> test = new ArrayList<>(mixed("x", 2, 0));
        test.addAll(List.of(scenario("hidden-1"), scenario("hidden-2")));
        OptimizationResult result =
                base(Split.explicit(mixed("t", 6, 0), mixed("v", 5, 0), test)).build().run();

        assertThat(result.bestScores().validationMean()).isEqualTo(1.0);
        assertThat(result.bestScores().testMean()).isBetween(0.83, 0.85);
        assertThat(result.verdict().reasons()).noneMatch(r -> r.contains("overfitting"));
        assertThat(result.generalized()).as(result.verdict().reasons().toString()).isTrue();
    }

    @Test
    void aLargeTestSplit_keepsTheStrictFloor() {
        // 100-scenario splits: one standard error is 0.07, so the 0.10 floor still applies and a
        // 0.16 validation-to-test drop is flagged.
        List<EvalScenario> test = new ArrayList<>(mixed("x", 20, 0));
        for (int i = 1; i <= 20; i++) {
            test.add(scenario("hidden-" + i));
        }
        OptimizationResult result =
                base(Split.explicit(mixed("t", 25, 0), mixed("v", 25, 0), test)).build().run();

        assertThat(result.bestScores().validationMean()).isEqualTo(1.0);
        assertThat(result.bestScores().testMean()).isBetween(0.83, 0.85);
        assertThat(result.verdict().reasons()).anyMatch(r -> r.contains("overfitting"));
        assertThat(result.generalized()).isFalse();
    }

    @Test
    void reachingTheTargetOnValidationIsNotSuccessWhenTheSealedTestDisagrees() {
        // The seed already covers every validation category, so the run stops at once on target,
        // but the test split is the category it never learned.
        Candidate seed =
                Candidate.of(PARAM, "You are helpful. [L:units] [L:dates] [L:currency] [L:names]");
        List<EvalScenario> test = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            test.add(scenario("hidden-" + i));
        }
        OptimizationResult result =
                base(Split.explicit(mixed("t", 3, 0), mixed("v", 3, 0), test))
                        .seed(seed)
                        .build()
                        .run();

        assertThat(result.stopReason()).isEqualTo(StopReason.TARGET_REACHED);
        assertThat(result.bestScores().validationMean()).isEqualTo(1.0);
        assertThat(result.bestScores().testMean()).isEqualTo(0.2);
        assertThat(result.generalized()).isFalse();
        assertThat(result.verdict().reasons())
                .anyMatch(r -> r.contains("validation target") && r.contains("sealed test"));
    }

    @Test
    void reachingTheTargetIsAcceptedWhenTheSealedTestAgrees() {
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter()).build().run();

        assertThat(result.stopReason()).isEqualTo(StopReason.TARGET_REACHED);
        assertThat(result.verdict().reasons()).noneMatch(r -> r.contains("validation target"));
        assertThat(result.generalized()).isTrue();
    }
}
