package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.eval.support.StubJudge;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Configuration errors are thrown from build(), before any LLM call, and say how to fix them. */
class PromptOptimizerBuilderTest {

    private static PromptOptimizer.Builder valid() {
        return SimulationSupport.optimizer(SimulationSupport.lessonRewriter());
    }

    private static void assertRejected(PromptOptimizer.Builder builder, String... messageParts) {
        assertThatThrownBy(builder::build)
                .isInstanceOf(OptimizerConfigurationException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains(messageParts));
    }

    @Test
    void aCompleteConfigurationBuilds() {
        assertThat(valid().build()).isNotNull();
    }

    @Test
    void everyRequiredSettingIsChecked() {
        assertRejected(PromptOptimizer.builder(), "seed is required");
        assertRejected(valid().system(null), "system is required");
        assertRejected(valid().criteria(List.of()), "at least one criterion");
        assertRejected(valid().scenarios(List.of()), "scenarios are required");
        assertRejected(valid().rewriter(null), "rewriter is required");
        assertRejected(valid().seed(null), "seed is required");
        assertRejected(valid().budget(null), "budget");
        assertRejected(valid().budget(OptimizerBudget.builder().build()), "at least one cap");
    }

    @Test
    void sideEffectsMustBeAcknowledged() {
        PromptOptimizer.Builder builder =
                PromptOptimizer.builder()
                        .seed(Candidate.of(SimulationSupport.PARAM, "x"))
                        .system(SimulationSupport.system())
                        .criteria(List.of(SimulationSupport.lessonCriterion()))
                        .scenarios(SimulationSupport.scenarios(15))
                        .rewriter(SimulationSupport.lessonRewriter())
                        .budget(OptimizerBudget.builder().maxRounds(3).build());
        assertRejected(builder, "acknowledgeSideEffects", "test doubles");
        assertThat(builder.acknowledgeSideEffects().build()).isNotNull();
    }

    @Test
    void numericSettingsAreRangeChecked() {
        assertRejected(valid().targetValidationMean(0), "targetValidationMean");
        assertRejected(valid().targetValidationMean(1.5), "targetValidationMean");
        assertRejected(valid().patience(0), "patience");
        assertRejected(valid().batchSize(0), "batchSize");
        assertRejected(valid().parallelism(0), "parallelism");
        assertRejected(valid().failureBreakerLimit(0), "failureBreakerLimit");
        assertRejected(valid().maxFailuresInPrompt(0), "maxFailuresInPrompt");
        assertRejected(valid().feedbackChars(10), "feedbackChars");
        assertRejected(valid().clock(null), "clock");
    }

    @Test
    void splitProblemsSurfaceWithCountsAndFixes() {
        assertRejected(
                valid().scenarios(SimulationSupport.scenarios(2)), "split has", "at least 5");
        assertRejected(
                valid().split(Split.ratios(0.6, 0.4, 0)), "test split is empty", "allowNoTest");
        assertRejected(valid().split(Split.ratios(0.5, 0.5, 0.5)), "sum to 1.0");
    }

    @Test
    void anExplicitSplitDoesNotNeedTheScenarioList() {
        var all = SimulationSupport.scenarios(15);
        assertThat(
                        valid().scenarios(List.of())
                                .split(
                                        Split.explicit(
                                                all.subList(0, 30),
                                                all.subList(30, 45),
                                                all.subList(45, 60)))
                                .build())
                .isNotNull();
    }

    @Test
    void theSeedMustSatisfyItsOwnConstraints() {
        assertRejected(
                valid().constraints(PromptConstraints.builder().mustContain("{{input}}").build()),
                "seed violates its own constraints",
                "{{input}}");
    }

    @Test
    void parameterDescriptionsMustNameRealParameters() {
        assertRejected(valid().parameterDescription("nope", "x"), "unknown parameter \"nope\"");
        assertThat(
                        valid().parameterDescription(SimulationSupport.PARAM, "the agent prompt")
                                .build())
                .isNotNull();
    }

    @Test
    void aRolloutBudgetTooSmallForOneRoundIsRejectedWithTheMinimum() {
        // 18 validation + (18 + 2*12) reserve + 2*4 batch = 68 with the default 60-scenario split
        assertRejected(
                valid().budget(OptimizerBudget.builder().maxRollouts(67).build()),
                "too small",
                "at least 68");
        assertThat(valid().budget(OptimizerBudget.builder().maxRollouts(68).build()).build())
                .isNotNull();
    }

    @Test
    void nonRolloutBudgetsAreNotRolloutChecked() {
        assertThat(
                        valid().budget(
                                        OptimizerBudget.builder()
                                                .maxDuration(Duration.ofSeconds(5))
                                                .build())
                                .build())
                .isNotNull();
    }

    @Test
    void usingTheSameClientAsRewriterAndJudgeWarns() {
        StubJudge shared = SimulationSupport.lessonRewriter();
        OptimizationResult result =
                valid().rewriter(shared)
                        .judge(shared)
                        .budget(OptimizerBudget.builder().maxRounds(1).build())
                        .build()
                        .run();

        assertThat(result.warnings())
                .anyMatch(w -> w.contains("same client") && w.contains("different model"));
        OptimizationResult separate =
                valid().judge(StubJudge.always("x"))
                        .budget(OptimizerBudget.builder().maxRounds(1).build())
                        .build()
                        .run();
        assertThat(separate.warnings()).noneMatch(w -> w.contains("same client"));
    }

    @Test
    void listenerMustNotBeNull() {
        assertThatThrownBy(() -> valid().listener(null)).isInstanceOf(NullPointerException.class);
    }
}
