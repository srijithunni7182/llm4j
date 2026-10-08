package io.github.llm4j.eval.criteria;

import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.ConversationJudgeCondition;
import io.github.llm4j.eval.judge.JudgeVerdict;
import io.github.llm4j.eval.judge.LlmJudgeCondition;
import io.github.llm4j.eval.judge.RagContextCondition;
import io.github.llm4j.eval.judge.Transcript;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Factories that adapt eval4j's existing conditions and assertions into {@link Criterion}s.
 *
 * <pre>{@code
 * List<Criterion> criteria = List.of(
 *     Criteria.judged(presets.correctness("36")),
 *     Criteria.assertion("used-calculator", r -> assertThat((AgentResult) r).usesTool("calculator")),
 *     Criteria.guardrail("no-pii", r -> assertThat(text(r)).doesNotContain("@")));
 * }</pre>
 */
public final class Criteria {

    private Criteria() {}

    /** A judged metric: score and reason come from the condition's {@code evaluate}. */
    public static Criterion judged(LlmJudgeCondition condition) {
        Objects.requireNonNull(condition, "condition cannot be null");
        return judged(condition.getName(), condition::evaluate, condition.getThreshold());
    }

    public static Criterion judged(RagContextCondition condition) {
        Objects.requireNonNull(condition, "condition cannot be null");
        return judged(
                condition.getMetric().displayName(), condition::evaluate, condition.getThreshold());
    }

    /**
     * Conversation metrics judge a {@link Transcript}; {@code toTranscript} builds one from the
     * output.
     */
    public static Criterion judged(
            ConversationJudgeCondition condition, Function<Object, Transcript> toTranscript) {
        Objects.requireNonNull(condition, "condition cannot be null");
        Objects.requireNonNull(toTranscript, "toTranscript cannot be null");
        return judged(
                condition.getMetric().displayName(),
                actual -> condition.evaluate(toTranscript.apply(actual)),
                condition.getThreshold());
    }

    /** A judged metric from any function returning a {@link JudgeVerdict}. */
    public static Criterion judged(
            String name, Function<Object, JudgeVerdict> evaluator, double passThreshold) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(evaluator, "evaluator cannot be null");
        return new Simple(
                name,
                (scenario, output) -> {
                    JudgeVerdict verdict = evaluator.apply(output);
                    return new CriterionResult(
                            verdict.score(), verdict.score() >= passThreshold, verdict.reason());
                });
    }

    /**
     * A deterministic check that throws {@link AssertionError} when the output is wrong. Passing
     * scores 1, failing scores 0 with the assertion message as feedback.
     */
    public static Criterion assertion(String name, Consumer<Object> check) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(check, "check cannot be null");
        return new Simple(
                name,
                (scenario, output) -> {
                    try {
                        check.accept(output);
                        return CriterionResult.pass("");
                    } catch (AssertionError e) {
                        return CriterionResult.fail(e.getMessage());
                    }
                });
    }

    /** Like {@link #assertion(String, Consumer)} but the check also receives the scenario. */
    public static Criterion scenarioAssertion(
            String name, java.util.function.BiConsumer<EvalScenario, Object> check) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(check, "check cannot be null");
        return new Simple(
                name,
                (scenario, output) -> {
                    try {
                        check.accept(scenario, output);
                        return CriterionResult.pass("");
                    } catch (AssertionError e) {
                        return CriterionResult.fail(e.getMessage());
                    }
                });
    }

    /** Wraps a criterion so that failing it forces the scenario score to 0. */
    public static Criterion guardrail(Criterion inner) {
        Objects.requireNonNull(inner, "inner cannot be null");
        return new Wrapper(inner, true, inner.weight());
    }

    /** A deterministic guardrail: an assertion that must hold. */
    public static Criterion guardrail(String name, Consumer<Object> check) {
        return guardrail(assertion(name, check));
    }

    /** Gives a criterion a non-default weight in the scenario's mean. */
    public static Criterion weighted(Criterion inner, double weight) {
        Objects.requireNonNull(inner, "inner cannot be null");
        if (weight < 0) {
            throw new IllegalArgumentException("weight cannot be negative, got: " + weight);
        }
        return new Wrapper(inner, inner.isGuardrail(), weight);
    }

    /**
     * A criterion built per scenario, e.g. correctness against that scenario's expected output:
     * {@code perScenario("correctness", s ->
     * Criteria.judged(presets.correctness(s.expectedOutput())))}.
     */
    public static Criterion perScenario(String name, Function<EvalScenario, Criterion> factory) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(factory, "factory cannot be null");
        return new Simple(
                name, (scenario, output) -> factory.apply(scenario).score(scenario, output));
    }

    private record Simple(
            String name, java.util.function.BiFunction<EvalScenario, Object, CriterionResult> fn)
            implements Criterion {
        @Override
        public CriterionResult score(EvalScenario scenario, Object output) {
            return fn.apply(scenario, output);
        }
    }

    private record Wrapper(Criterion inner, boolean guardrail, double weight) implements Criterion {
        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public CriterionResult score(EvalScenario scenario, Object output) {
            return inner.score(scenario, output);
        }

        @Override
        public boolean isGuardrail() {
            return guardrail;
        }

        @Override
        public double weight() {
            return weight;
        }
    }
}
