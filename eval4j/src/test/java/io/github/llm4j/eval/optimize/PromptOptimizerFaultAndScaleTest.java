package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.support.StubJudge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline fault-injection and scale checks (the live equivalents are in the verification plan). */
class PromptOptimizerFaultAndScaleTest {

    /** Wraps the lesson rewriter and misbehaves on a fixed fraction of calls. */
    private static StubJudge flakyRewriter(int garbageEveryN, int throwEveryN) {
        StubJudge good = SimulationSupport.slowRewriter();
        AtomicInteger calls = new AtomicInteger();
        return new StubJudge(
                request -> {
                    int n = calls.incrementAndGet();
                    if (n % garbageEveryN == 0) {
                        return "Sorry, I can't produce JSON right now.";
                    }
                    if (throwEveryN > 0 && n % throwEveryN == 0) {
                        throw new IllegalStateException("rewriter timed out");
                    }
                    return good.chat(request).getContent();
                });
    }

    @Test
    void aFlakyRewriterSlowsProgressButNeverBreaksTheRun() {
        OptimizationResult result =
                SimulationSupport.optimizer(flakyRewriter(3, 5))
                        .patience(50)
                        .budget(OptimizerBudget.builder().maxRollouts(4000).maxRounds(80).build())
                        .build()
                        .run();

        assertThat(result.trace())
                .extracting(Round::action)
                .contains(RoundAction.REWRITE_FAILED, RoundAction.ACCEPTED);
        assertThat(result.stopReason()).isEqualTo(StopReason.TARGET_REACHED);
        assertThat(result.generalized()).isTrue();
        assertThat(result.trace().stream().filter(r -> r.action() == RoundAction.REWRITE_FAILED))
                .isNotEmpty();
    }

    @Test
    void anAlwaysThrowingRewriterEndsWithNoProgressAndTheSeed() {
        StubJudge broken =
                new StubJudge(
                        r -> {
                            throw new IllegalStateException("provider down");
                        });
        OptimizationResult result = SimulationSupport.optimizer(broken).patience(3).build().run();

        assertThat(result.stopReason()).isEqualTo(StopReason.NO_PROGRESS);
        assertThat(result.trace())
                .extracting(Round::action)
                .containsOnly(RoundAction.REWRITE_FAILED);
        assertThat(result.trace().get(0).note()).contains("provider down");
        assertThat(result.best()).isEqualTo(result.seed());
        // a failed call is not retried (the provider layer owns retries): one call per round
        assertThat(result.cost().rewriterCalls()).isEqualTo(3);
    }

    @Test
    void capsHoldAndTracesAreIdenticalAtEveryParallelism() {
        List<OptimizationResult> results = new ArrayList<>();
        for (int parallelism : new int[] {1, 4, 16}) {
            SimulationSupport.CountingSystem system =
                    new SimulationSupport.CountingSystem(SimulationSupport.system());
            OptimizationResult result =
                    SimulationSupport.optimizer(SimulationSupport.slowRewriter())
                            .system(system)
                            .parallelism(parallelism)
                            .budget(OptimizerBudget.builder().maxRollouts(150).build())
                            .targetValidationMean(1.0)
                            .build()
                            .run();
            assertThat(system.count()).as("parallelism " + parallelism).isLessThanOrEqualTo(150);
            results.add(result);
        }
        assertThat(results.get(1).trace()).isEqualTo(results.get(0).trace());
        assertThat(results.get(2).trace()).isEqualTo(results.get(0).trace());
        assertThat(results.get(2).cost().rollouts()).isEqualTo(results.get(0).cost().rollouts());
    }

    @Test
    void onlyTheCheckpointDirectoryIsWritten(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("sentinel.txt"), "untouched");
        Path checkpoint = root.resolve("ckpt");

        SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                .checkpointDir(checkpoint)
                .build()
                .run();

        try (var entries = Files.list(root)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("sentinel.txt", "ckpt");
        }
        assertThat(Files.readString(root.resolve("sentinel.txt"))).isEqualTo("untouched");
        try (var entries = Files.list(checkpoint)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .containsExactly(Checkpointer.FILE_NAME);
        }
    }

    @Test
    void fiveHundredScenariosAndFiftyRoundsFinishQuicklyWithBoundedMemoryOfTrace() {
        List<EvalScenario> many = SimulationSupport.scenarios(125); // 500 scenarios
        OptimizationResult result =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(60),
                        () ->
                                SimulationSupport.optimizer(SimulationSupport.slowRewriter())
                                        .scenarios(many)
                                        .split(Split.ratios(0.5, 0.3, 0.2).seed(3))
                                        .targetValidationMean(1.0)
                                        .patience(60)
                                        .budget(OptimizerBudget.builder().maxRounds(50).build())
                                        .build()
                                        .run());

        assertThat(result.trace().size()).isLessThanOrEqualTo(50);
        assertThat(result.bestScores().validation()).hasSize(150);
        assertThat(result.bestScores().test()).hasSize(100);
    }
}
