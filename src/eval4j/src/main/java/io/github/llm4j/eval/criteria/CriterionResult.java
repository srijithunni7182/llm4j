package io.github.llm4j.eval.criteria;

/**
 * The outcome of one {@link Criterion} on one output.
 *
 * @param score 0.0-1.0
 * @param passed whether the criterion counts as met (judged: score at or above its threshold)
 * @param feedback human-readable reason or assertion message; may be empty
 */
public record CriterionResult(double score, boolean passed, String feedback) {

    public CriterionResult {
        if (Double.isNaN(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be within 0.0-1.0, got: " + score);
        }
        feedback = feedback == null ? "" : feedback;
    }

    public static CriterionResult pass(String feedback) {
        return new CriterionResult(1.0, true, feedback);
    }

    public static CriterionResult fail(String feedback) {
        return new CriterionResult(0.0, false, feedback);
    }
}
