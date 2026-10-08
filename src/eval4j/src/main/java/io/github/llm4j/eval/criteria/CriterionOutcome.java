package io.github.llm4j.eval.criteria;

/**
 * A {@link CriterionResult} tied to the criterion that produced it.
 *
 * @param error true when the criterion itself failed to run (e.g. the judge call threw) rather than
 *     judging the output badly — a signal of infrastructure trouble, not of quality
 */
public record CriterionOutcome(
        String criterion, CriterionResult result, boolean guardrail, boolean error) {}
