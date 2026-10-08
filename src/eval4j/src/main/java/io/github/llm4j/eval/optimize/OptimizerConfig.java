package io.github.llm4j.eval.optimize;

import io.github.llm4j.LLMClient;
import io.github.llm4j.eval.criteria.Criterion;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Validated, immutable settings for one optimizer, produced by {@code PromptOptimizer.Builder}. */
record OptimizerConfig(
        Candidate seed,
        SystemUnderTest system,
        List<Criterion> criteria,
        DataSplit split,
        LLMClient rewriter,
        double rewriterTemperature,
        Map<String, String> parameterDescriptions,
        PromptConstraints constraints,
        OptimizerBudget budget,
        double target,
        int patience,
        int batchSize,
        double gateMargin,
        double perfectThreshold,
        int maxFailuresInPrompt,
        int feedbackChars,
        double minTestGain,
        double maxOverfitGap,
        int parallelism,
        Path checkpointDir,
        OptimizerListener listener,
        UnaryOperator<String> redactor,
        List<LlmCallCounter> counters,
        Clock clock,
        long randomSeed,
        int failureBreakerLimit,
        List<String> warnings,
        String fingerprint) {

    /** Rollouts held back for the confirmation run and the seed/best test runs. */
    long finalPhaseReserve() {
        return split.validation().size() + 2L * split.test().size();
    }

    /**
     * Scenarios per batch actually used (the train split may be smaller than {@code batchSize}).
     */
    int effectiveBatchSize() {
        return Math.min(batchSize, split.train().size());
    }
}
