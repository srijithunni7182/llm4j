package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.criteria.Criteria;
import io.github.llm4j.eval.criteria.Criterion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The usage shown in docs/OPTIMIZER.md, compiled and run with stub models so it cannot rot. */
class OptimizerDocsExampleTest {

    @Test
    void theGuideExampleRunsAndProducesAReviewablePatch(
            @TempDir Path promptsDir, @TempDir Path reportDir) throws Exception {
        String currentPrompt = "You are a helpful assistant.";
        Criterion correctness =
                SimulationSupport.partialCreditCriterion(); // Criteria.judged(...) in real use
        Criterion noPii =
                Criteria.guardrail(
                        "no-pii",
                        out -> {
                            if (String.valueOf(out).contains("@")) {
                                throw new AssertionError("output contains an email address");
                            }
                        });

        PromptOptimizer optimizer =
                PromptOptimizer.builder()
                        .seed(Candidate.of("system-prompt", currentPrompt))
                        .system(SimulationSupport.system()) // builds your agent from the candidate
                        .criteria(List.of(correctness, noPii))
                        .scenarios(
                                SimulationSupport.scenarios(
                                        15)) // EvalScenarios.fromYamlResource(...) in real use
                        .split(Split.ratios(0.5, 0.3, 0.2).seed(42))
                        .rewriter(
                                SimulationSupport
                                        .lessonRewriter()) // a different model from the judge
                        .constraints(PromptConstraints.builder().maxChars(4000).build())
                        .budget(OptimizerBudget.builder().maxRollouts(600).maxRounds(30).build())
                        .targetValidationMean(0.9)
                        .checkpointDir(reportDir.resolve("checkpoint"))
                        .acknowledgeSideEffects()
                        .build();

        PromptOptimizer.Estimate estimate =
                optimizer.estimate(); // what the budget allows, before spending
        OptimizationResult result = optimizer.run();

        assertThat(estimate.maxRollouts()).isEqualTo(600);
        assertThat(result.generalized())
                .isTrue(); // sealed test split, confirmation and guardrails agree
        result.writeReport(reportDir); // optimizer-trace.json + optimizer-report.md
        PromptPatch patch = result.toPatch(); // writes nothing by itself
        assertThat(patch.unifiedDiff())
                .contains("-You are a helpful assistant.")
                .contains("[L:units]");
        patch.applyTo(promptsDir); // your explicit decision to apply it
        assertThat(Files.readString(promptsDir.resolve("system-prompt.txt"))).contains("[L:units]");
    }
}
