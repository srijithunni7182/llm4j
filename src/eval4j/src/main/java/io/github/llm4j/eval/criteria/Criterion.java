package io.github.llm4j.eval.criteria;

import io.github.llm4j.eval.dataset.EvalScenario;

/**
 * One way of scoring a system's output for a scenario: a deterministic check or a judged metric.
 * Unlike an AssertJ assertion, a criterion returns its verdict as data (score, pass/fail, reasons)
 * instead of throwing, so callers such as the prompt optimizer can act on <em>why</em> something
 * scored as it did. Build criteria with {@link Criteria}.
 */
public interface Criterion {

    /** Short, stable name used in reports and feedback. */
    String name();

    /**
     * Scores {@code output} (an {@code AgentResult}, {@code LLMResponse} or {@code String}) for the
     * scenario. Implementations should not throw for ordinary failures; {@link Scoring} converts an
     * unexpected exception into an errored result.
     */
    CriterionResult score(EvalScenario scenario, Object output);

    /** A guardrail must pass regardless of score: failing one forces the scenario score to 0. */
    default boolean isGuardrail() {
        return false;
    }

    /** Relative weight in the scenario's mean score (guardrails carry no weight). */
    default double weight() {
        return 1.0;
    }
}
