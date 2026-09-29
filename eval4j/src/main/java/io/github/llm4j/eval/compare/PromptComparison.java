package io.github.llm4j.eval.compare;

import io.github.llm4j.LLMClient;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.JudgeCache;
import io.github.llm4j.eval.judge.JudgeCalls;
import io.github.llm4j.eval.report.EvalRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Answers "is prompt/model variant B better than A?" by running both variants over a dataset and
 * judging each pair. Variants are plain functions from a scenario to an {@code AgentResult}, {@code
 * LLMResponse} or {@code String}, so any prompt, agent or model can be compared.
 *
 * <pre>{@code
 * PromptComparison.Result result = PromptComparison.using(judgeClient)
 *     .criteria("More helpful, accurate and concise for a customer-support answer")
 *     .variantA("current", s -> agentA.run(s.input()))
 *     .variantB("candidate", s -> agentB.run(s.input()))
 *     .scenarios(EvalScenarios.fromYamlResource("scenarios.yaml"))
 *     .run();
 * PromptComparisonAssertions.assertThat(result).doesNotRegress(0.05);
 * }</pre>
 *
 * A variant that throws counts as a loss for that variant on that scenario (and is reported), never
 * aborting the run.
 */
public final class PromptComparison {

    public enum Outcome {
        A_WINS,
        B_WINS,
        TIE,
        ERROR_A,
        ERROR_B,
        ERROR_BOTH
    }

    /** One scenario's result. */
    public record ScenarioOutcome(
            String scenario, Outcome outcome, boolean positionInconsistent, String reason) {}

    /** 95% Wilson score interval. */
    public record Interval(double low, double high) {}

    private final JudgeCalls calls;
    private String criteria;
    private String nameA = "A";
    private String nameB = "B";
    private Function<EvalScenario, Object> variantA;
    private Function<EvalScenario, Object> variantB;
    private List<EvalScenario> scenarios = List.of();
    private boolean swapPositions = true;
    private int samples = 1;

    private PromptComparison(JudgeCalls calls) {
        this.calls = calls;
    }

    public static PromptComparison using(LLMClient judge) {
        return new PromptComparison(
                JudgeCalls.using(Objects.requireNonNull(judge, "judge cannot be null")));
    }

    public PromptComparison criteria(String criteria) {
        this.criteria = criteria;
        return this;
    }

    public PromptComparison variantA(String name, Function<EvalScenario, Object> variant) {
        this.nameA = name;
        this.variantA = variant;
        return this;
    }

    public PromptComparison variantB(String name, Function<EvalScenario, Object> variant) {
        this.nameB = name;
        this.variantB = variant;
        return this;
    }

    public PromptComparison scenarios(List<EvalScenario> scenarios) {
        this.scenarios = scenarios;
        return this;
    }

    /** Judge each pair in both orders to cancel position bias (default true). */
    public PromptComparison swapPositions(boolean swapPositions) {
        this.swapPositions = swapPositions;
        return this;
    }

    public PromptComparison samples(int samples) {
        if (samples < 1) {
            throw new IllegalArgumentException("samples must be at least 1, got: " + samples);
        }
        this.samples = samples;
        return this;
    }

    public PromptComparison cache(JudgeCache cache) {
        return withCalls(calls.cache(cache));
    }

    public PromptComparison judgeIdentifier(String identifier) {
        return withCalls(calls.judgeIdentifier(identifier));
    }

    private PromptComparison withCalls(JudgeCalls newCalls) {
        PromptComparison copy = new PromptComparison(newCalls);
        copy.criteria = criteria;
        copy.nameA = nameA;
        copy.nameB = nameB;
        copy.variantA = variantA;
        copy.variantB = variantB;
        copy.scenarios = scenarios;
        copy.swapPositions = swapPositions;
        copy.samples = samples;
        return copy;
    }

    public Result run() {
        if (criteria == null || criteria.isBlank()) {
            throw new IllegalArgumentException("criteria is required");
        }
        if (variantA == null || variantB == null) {
            throw new IllegalArgumentException("both variantA and variantB are required");
        }
        if (scenarios == null || scenarios.isEmpty()) {
            throw new IllegalArgumentException("scenarios must not be empty");
        }
        PairwiseJudge judge =
                PairwiseJudge.using(calls, criteria).swapPositions(swapPositions).samples(samples);
        String metric = "Pairwise: " + nameA + " vs " + nameB;
        List<ScenarioOutcome> outcomes = new ArrayList<>();
        for (EvalScenario scenario : scenarios) {
            String name = scenario.toString();
            Object outA = null;
            Object outB = null;
            String errA = null;
            String errB = null;
            try {
                outA = variantA.apply(scenario);
            } catch (RuntimeException e) {
                errA = String.valueOf(e);
            }
            try {
                outB = variantB.apply(scenario);
            } catch (RuntimeException e) {
                errB = String.valueOf(e);
            }
            ScenarioOutcome outcome;
            if (errA != null && errB != null) {
                outcome =
                        new ScenarioOutcome(
                                name,
                                Outcome.ERROR_BOTH,
                                false,
                                nameA + " failed: " + errA + "; " + nameB + " failed: " + errB);
            } else if (errA != null) {
                outcome =
                        new ScenarioOutcome(
                                name, Outcome.ERROR_A, false, nameA + " failed: " + errA);
            } else if (errB != null) {
                outcome =
                        new ScenarioOutcome(
                                name, Outcome.ERROR_B, false, nameB + " failed: " + errB);
            } else {
                PairwiseJudge.PairResult r = judge.judge(scenario.input(), outA, outB);
                Outcome o =
                        switch (r.winner()) {
                            case A -> Outcome.A_WINS;
                            case B -> Outcome.B_WINS;
                            case TIE -> Outcome.TIE;
                        };
                outcome = new ScenarioOutcome(name, o, r.positionInconsistent(), r.reason());
            }
            outcomes.add(outcome);
            double score =
                    switch (outcome.outcome()) {
                        case B_WINS, ERROR_A -> 1.0;
                        case A_WINS, ERROR_B -> 0.0;
                        default -> 0.5;
                    };
            EvalRecorder.record(
                    metric, score, 0.5, name + ": " + outcome.reason(), calls.judgeIdentifier());
        }
        return new Result(nameA, nameB, outcomes);
    }

    /**
     * All per-scenario outcomes plus aggregates. Errors count as losses for the erroring variant.
     */
    public static final class Result {
        private final String nameA;
        private final String nameB;
        private final List<ScenarioOutcome> outcomes;

        Result(String nameA, String nameB, List<ScenarioOutcome> outcomes) {
            this.nameA = nameA;
            this.nameB = nameB;
            this.outcomes = List.copyOf(outcomes);
        }

        public String nameA() {
            return nameA;
        }

        public String nameB() {
            return nameB;
        }

        public List<ScenarioOutcome> outcomes() {
            return outcomes;
        }

        public int count(Outcome outcome) {
            return (int) outcomes.stream().filter(o -> o.outcome() == outcome).count();
        }

        public int winsA() {
            return count(Outcome.A_WINS) + count(Outcome.ERROR_B);
        }

        public int winsB() {
            return count(Outcome.B_WINS) + count(Outcome.ERROR_A);
        }

        public int ties() {
            return count(Outcome.TIE) + count(Outcome.ERROR_BOTH);
        }

        public int errors() {
            return count(Outcome.ERROR_A) + count(Outcome.ERROR_B) + count(Outcome.ERROR_BOTH);
        }

        public double winRateA() {
            return winsA() / (double) outcomes.size();
        }

        public double winRateB() {
            return winsB() / (double) outcomes.size();
        }

        public double tieRate() {
            return ties() / (double) outcomes.size();
        }

        /** Wilson interval on B's win rate among decisive (non-tie) scenarios; empty if none. */
        public Optional<Interval> candidateWinRateInterval() {
            int n = winsA() + winsB();
            if (n == 0) {
                return Optional.empty();
            }
            return Optional.of(wilson(winsB(), n));
        }

        @Override
        public String toString() {
            return String.format(
                    java.util.Locale.ROOT,
                    "%s vs %s over %d scenarios: %s wins %d, %s wins %d, ties %d, errors %d",
                    nameA,
                    nameB,
                    outcomes.size(),
                    nameA,
                    winsA(),
                    nameB,
                    winsB(),
                    ties(),
                    errors());
        }
    }

    static Interval wilson(int successes, int n) {
        double z = 1.96;
        double p = successes / (double) n;
        double denom = 1 + z * z / n;
        double center = (p + z * z / (2 * n)) / denom;
        double margin = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom;
        return new Interval(Math.max(0, center - margin), Math.min(1, center + margin));
    }
}
