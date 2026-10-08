package io.github.llm4j.eval.criteria;

import java.util.List;

/**
 * The result of scoring one system output on one scenario.
 *
 * @param scenario scenario name
 * @param score 0.0-1.0; forced to 0 when a guardrail failed
 * @param guardrailViolated whether any guardrail criterion failed
 * @param outcomes per-criterion results, in criterion order
 * @param output the output text that was scored (may be redacted by callers before persisting)
 * @param feedback failed-assertion messages and judge reasons, ready to show a rewriter
 * @param infrastructureFailure true when the system threw or a criterion errored, so the score says
 *     little about quality
 */
public record Scorecard(
        String scenario,
        double score,
        boolean guardrailViolated,
        List<CriterionOutcome> outcomes,
        String output,
        String feedback,
        boolean infrastructureFailure) {

    public Scorecard {
        outcomes = List.copyOf(outcomes);
    }
}
