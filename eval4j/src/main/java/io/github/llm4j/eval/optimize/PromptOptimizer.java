package io.github.llm4j.eval.optimize;

import io.github.llm4j.LLMClient;
import io.github.llm4j.eval.criteria.Criterion;
import io.github.llm4j.eval.dataset.EvalScenario;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Autonomously improves the text parameters of an AI system (first: an agent's prompt) against
 * eval4j criteria, in the style of GEPA: it repeatedly picks a promising candidate from a Pareto
 * pool, runs it on a few <em>training</em> scenarios, has an LLM rewrite the text based on why it
 * failed, and keeps the rewrite only if it earns its place — selecting on a <em>validation</em>
 * split and finally verifying on a sealed <em>test</em> split.
 *
 * <p>It <strong>runs your system many times</strong> and calls an LLM to rewrite text, so it is a
 * tool that changes things, unlike the rest of eval4j which only measures. The result is a
 * reviewable {@link PromptPatch}; nothing is written to your files unless you apply it. Optimizing
 * against an LLM judge can raise its score without improving real quality: use deterministic
 * guardrails, keep the test split sealed, and read the diff.
 *
 * <pre>{@code
 * OptimizationResult result = PromptOptimizer.builder()
 *     .seed(Candidate.of("system-prompt", currentPrompt))
 *     .system((candidate, scenario) -> buildAgent(candidate.get("system-prompt")).run(scenario.input()))
 *     .criteria(List.of(Criteria.judged(correctness), Criteria.guardrail("no-pii", noPii)))
 *     .scenarios(EvalScenarios.fromYamlResource("scenarios.yaml"))
 *     .rewriter(rewriterClient)
 *     .budget(OptimizerBudget.builder().maxRollouts(600).build())
 *     .acknowledgeSideEffects()
 *     .build()
 *     .run();
 * if (result.generalized()) { result.toPatch().applyTo(promptsDir); }
 * }</pre>
 */
public final class PromptOptimizer {

    private final OptimizerConfig config;

    private PromptOptimizer(OptimizerConfig config) {
        this.config = config;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Runs the optimization. Safe to call more than once; each call is an independent run. */
    public OptimizationResult run() {
        return new OptimizationRun(config).execute();
    }

    /** An upper bound on what the configured budget allows, before anything runs. */
    public Estimate estimate() {
        OptimizerBudget budget = config.budget();
        long validation = config.split().validation().size();
        long reserve = config.finalPhaseReserve();
        long batch = Math.max(1, config.effectiveBatchSize());
        long rounds = budget.maxRounds() > 0 ? budget.maxRounds() : -1;
        long rollouts = budget.maxRollouts() > 0 ? budget.maxRollouts() : -1;
        if (rollouts > 0) {
            long byRollouts = Math.max(0, rollouts - validation - reserve) / batch;
            rounds = rounds > 0 ? Math.min(rounds, byRollouts) : byRollouts;
        }
        if (rollouts < 0 && rounds > 0) {
            rollouts = validation + rounds * (2 * batch + validation) + reserve;
        }
        return new Estimate(rollouts, rounds, rounds > 0 ? 2 * rounds : -1);
    }

    /**
     * Upper bounds derived from the budget; {@code -1} means "unbounded by rollouts or rounds" (for
     * example when only a duration cap is set).
     */
    public record Estimate(long maxRollouts, long maxRounds, long maxRewriterCalls) {

        /**
         * Rollouts × the LLM calls one rollout makes (agent + judge calls), plus rewriter calls.
         */
        public long maxLlmCalls(int callsPerRollout) {
            if (maxRollouts < 0) {
                return -1;
            }
            return maxRollouts * callsPerRollout + Math.max(0, maxRewriterCalls);
        }
    }

    public static final class Builder {
        private Candidate seed;
        private SystemUnderTest system;
        private List<Criterion> criteria = List.of();
        private List<EvalScenario> scenarios = List.of();
        private Split split = Split.ratios(0.5, 0.3, 0.2);
        private LLMClient rewriter;
        private LLMClient judge;
        private double rewriterTemperature = 0.7;
        private final Map<String, String> descriptions = new LinkedHashMap<>();
        private PromptConstraints constraints = PromptConstraints.none();
        private OptimizerBudget budget;
        private double target = 0.9;
        private int patience = 5;
        private int batchSize = 4;
        private double gateMargin = 0.0;
        private double perfectThreshold = 0.99;
        private int maxFailuresInPrompt = 4;
        private int feedbackChars = 400;
        private double minTestGain = 0.02;
        private double maxOverfitGap = 0.10;
        private int parallelism = 1;
        private Path checkpointDir;
        private OptimizerListener listener = new OptimizerListener() {};
        private UnaryOperator<String> redactor;
        private final List<LlmCallCounter> counters = new ArrayList<>();
        private Clock clock = Clock.systemUTC();
        private long randomSeed;
        private int failureBreakerLimit = 5;
        private boolean sideEffectsAcknowledged;

        public Builder seed(Candidate seed) {
            this.seed = seed;
            return this;
        }

        public Builder system(SystemUnderTest system) {
            this.system = system;
            return this;
        }

        public Builder criteria(List<Criterion> criteria) {
            this.criteria = List.copyOf(criteria);
            return this;
        }

        public Builder scenarios(List<EvalScenario> scenarios) {
            this.scenarios = List.copyOf(scenarios);
            return this;
        }

        public Builder split(Split split) {
            this.split = split;
            return this;
        }

        /** The model that rewrites prompts. Should differ from the judge model. */
        public Builder rewriter(LLMClient rewriter) {
            this.rewriter = rewriter;
            return this;
        }

        /** Optional: the judge's client, only used to warn when it is the rewriter's client too. */
        public Builder judge(LLMClient judge) {
            this.judge = judge;
            return this;
        }

        public Builder rewriterTemperature(double temperature) {
            this.rewriterTemperature = temperature;
            return this;
        }

        /**
         * What a parameter is for, shown to the rewriter (e.g. "system prompt for a refund agent").
         */
        public Builder parameterDescription(String parameter, String description) {
            descriptions.put(parameter, description);
            return this;
        }

        public Builder constraints(PromptConstraints constraints) {
            this.constraints = constraints;
            return this;
        }

        public Builder budget(OptimizerBudget budget) {
            this.budget = budget;
            return this;
        }

        /** Stop when the best validation mean reaches this (0-1] with no guardrail violations. */
        public Builder targetValidationMean(double target) {
            this.target = target;
            return this;
        }

        /** Stop after this many consecutive rounds that add nothing to the frontier. */
        public Builder patience(int patience) {
            this.patience = patience;
            return this;
        }

        /** Training scenarios per round (default 4). */
        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /** How much a child must beat its parent by on the batch to be evaluated further. */
        public Builder gateMargin(double gateMargin) {
            this.gateMargin = gateMargin;
            return this;
        }

        public Builder perfectThreshold(double perfectThreshold) {
            this.perfectThreshold = perfectThreshold;
            return this;
        }

        public Builder maxFailuresInPrompt(int maxFailuresInPrompt) {
            this.maxFailuresInPrompt = maxFailuresInPrompt;
            return this;
        }

        public Builder feedbackChars(int feedbackChars) {
            this.feedbackChars = feedbackChars;
            return this;
        }

        /** Minimum test-split improvement over the seed for the result to count as generalized. */
        public Builder minTestGain(double minTestGain) {
            this.minTestGain = minTestGain;
            return this;
        }

        /**
         * Largest tolerated drop from validation to test (or from selection to confirmation). For
         * the validation-to-test gap this is a floor: tiny splits are noisy, so the tolerance
         * widens to one standard error of the difference when that is larger.
         */
        public Builder maxOverfitGap(double maxOverfitGap) {
            this.maxOverfitGap = maxOverfitGap;
            return this;
        }

        /** Rollouts run in parallel; your system and criteria must be thread-safe. */
        public Builder parallelism(int parallelism) {
            this.parallelism = parallelism;
            return this;
        }

        /** Directory for the per-round checkpoint; enables resume. */
        public Builder checkpointDir(Path checkpointDir) {
            this.checkpointDir = checkpointDir;
            return this;
        }

        public Builder listener(OptimizerListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener cannot be null");
            return this;
        }

        /** Scrubs outputs and feedback before they reach the rewriter, traces and checkpoints. */
        public Builder redactor(UnaryOperator<String> redactor) {
            this.redactor = redactor;
            return this;
        }

        /** Include calls through these counters in the LLM-call budget and cost. */
        public Builder trackCalls(LlmCallCounter... counters) {
            this.counters.addAll(List.of(counters));
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** Seed for batch sampling and parent selection (the split has its own seed). */
        public Builder randomSeed(long randomSeed) {
            this.randomSeed = randomSeed;
            return this;
        }

        /** Consecutive infrastructure failures (system or criterion errors) that abort the run. */
        public Builder failureBreakerLimit(int limit) {
            this.failureBreakerLimit = limit;
            return this;
        }

        /**
         * Required. Confirms you know the system will be executed many times and that tools which
         * write, send or charge must be replaced by test doubles.
         */
        public Builder acknowledgeSideEffects() {
            this.sideEffectsAcknowledged = true;
            return this;
        }

        public PromptOptimizer build() {
            require(
                    seed != null,
                    "seed is required: Candidate.of(\"system-prompt\", currentPrompt)");
            require(
                    system != null,
                    "system is required: a SystemUnderTest that builds and runs your agent");
            require(!criteria.isEmpty(), "at least one criterion is required (see Criteria)");
            require(
                    split == null || split.isExplicit() || !scenarios.isEmpty(),
                    "scenarios are required");
            require(rewriter != null, "rewriter is required: an LLMClient that rewrites prompts");
            require(split != null, "split cannot be null");
            require(
                    sideEffectsAcknowledged,
                    "call acknowledgeSideEffects(): the optimizer runs your system hundreds of times,"
                            + " so tools that write, send or charge must be replaced by test doubles");
            require(
                    budget != null && budget.hasAnyCap(),
                    "a budget with at least one cap is required: OptimizerBudget.builder().maxRollouts(..)");
            require(
                    target > 0 && target <= 1,
                    "targetValidationMean must be in (0, 1], got " + target);
            require(patience >= 1, "patience must be at least 1");
            require(batchSize >= 1, "batchSize must be at least 1");
            require(parallelism >= 1, "parallelism must be at least 1");
            require(failureBreakerLimit >= 1, "failureBreakerLimit must be at least 1");
            require(maxFailuresInPrompt >= 1, "maxFailuresInPrompt must be at least 1");
            require(feedbackChars >= 50, "feedbackChars must be at least 50");
            require(clock != null, "clock cannot be null");

            DataSplit dataSplit = split.apply(scenarios);
            String violation = constraints.violation(seed);
            require(violation == null, "the seed violates its own constraints: " + violation);
            for (String parameter : descriptions.keySet()) {
                require(
                        seed.parameterNames().contains(parameter),
                        "parameterDescription refers to unknown parameter \"" + parameter + "\"");
            }

            OptimizerConfig draft = config(dataSplit, List.of(), "");
            checkBudgetCanCoverOneRound(draft);
            List<String> warnings = new ArrayList<>();
            if (judge != null && judge == rewriter) {
                warnings.add(
                        "the rewriter and the judge are the same client; the model is partly grading"
                                + " its own edits. Use a different model for the judge.");
            }
            return new PromptOptimizer(config(dataSplit, warnings, fingerprint(dataSplit)));
        }

        private void checkBudgetCanCoverOneRound(OptimizerConfig draft) {
            if (budget.maxRollouts() <= 0) {
                return;
            }
            long needed =
                    draft.split().validation().size()
                            + draft.finalPhaseReserve()
                            + 2L * draft.effectiveBatchSize();
            require(
                    budget.maxRollouts() >= needed,
                    "maxRollouts "
                            + budget.maxRollouts()
                            + " is too small: scoring the seed, one full round and the final"
                            + " confirmation/test runs need at least "
                            + needed
                            + ". Raise it, use smaller splits, or lower batchSize.");
        }

        private OptimizerConfig config(
                DataSplit dataSplit, List<String> warnings, String fingerprint) {
            return new OptimizerConfig(
                    seed,
                    system,
                    criteria,
                    dataSplit,
                    rewriter,
                    rewriterTemperature,
                    Map.copyOf(descriptions),
                    constraints,
                    budget,
                    target,
                    patience,
                    batchSize,
                    gateMargin,
                    perfectThreshold,
                    maxFailuresInPrompt,
                    feedbackChars,
                    minTestGain,
                    maxOverfitGap,
                    parallelism,
                    checkpointDir,
                    listener,
                    redactor,
                    List.copyOf(counters),
                    clock,
                    randomSeed,
                    failureBreakerLimit,
                    List.copyOf(warnings),
                    fingerprint);
        }

        /** Identifies the run so a checkpoint from a different configuration is never resumed. */
        private String fingerprint(DataSplit dataSplit) {
            StringBuilder sb = new StringBuilder();
            seed.parameters()
                    .forEach((k, v) -> sb.append(k).append('=').append(v).append('\u0001'));
            for (List<EvalScenario> part :
                    List.of(dataSplit.train(), dataSplit.validation(), dataSplit.test())) {
                sb.append('|');
                part.forEach(
                        s ->
                                sb.append(s.name())
                                        .append('\u0002')
                                        .append(s.input())
                                        .append('\u0001'));
            }
            criteria.forEach(c -> sb.append('#').append(c.name()));
            sb.append(
                    String.format(
                            Locale.ROOT,
                            "|%d|%d|%d|%.6f|%.6f|%.6f",
                            randomSeed,
                            batchSize,
                            patience,
                            target,
                            gateMargin,
                            perfectThreshold));
            try {
                byte[] hash =
                        MessageDigest.getInstance("SHA-256")
                                .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) {
                    hex.append(String.format(Locale.ROOT, "%02x", b));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new OptimizerConfigurationException(message);
            }
        }
    }
}
