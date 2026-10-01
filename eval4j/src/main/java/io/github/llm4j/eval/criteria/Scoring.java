package io.github.llm4j.eval.criteria;

import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.JudgeCalls;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns one output plus a list of {@link Criterion}s into a {@link Scorecard}.
 *
 * <p>Scoring rule: if any guardrail fails the scenario scores 0; otherwise the score is the
 * weight-averaged score of the non-guardrail criteria (1.0 when there are only guardrails and they
 * all pass). A criterion that throws is recorded as an errored 0 rather than aborting the run.
 */
public final class Scoring {

    /** Feedback is truncated per criterion so it stays useful inside a prompt. */
    public static final int DEFAULT_FEEDBACK_CHARS = 400;

    private Scoring() {}

    public static Scorecard score(EvalScenario scenario, Object output, List<Criterion> criteria) {
        return score(scenario, output, criteria, DEFAULT_FEEDBACK_CHARS);
    }

    public static Scorecard score(
            EvalScenario scenario, Object output, List<Criterion> criteria, int feedbackChars) {
        List<CriterionOutcome> outcomes = new ArrayList<>();
        boolean guardrailViolated = false;
        boolean anyError = false;
        double weighted = 0;
        double totalWeight = 0;
        Set<String> feedback = new LinkedHashSet<>();
        for (Criterion criterion : criteria) {
            CriterionResult result;
            boolean error = false;
            try {
                result = criterion.score(scenario, output);
            } catch (RuntimeException e) {
                result =
                        CriterionResult.fail(
                                "criterion \""
                                        + criterion.name()
                                        + "\" errored: "
                                        + e.getMessage());
                error = true;
                anyError = true;
            }
            boolean guardrail = criterion.isGuardrail();
            outcomes.add(new CriterionOutcome(criterion.name(), result, guardrail, error));
            if (guardrail) {
                if (!result.passed()) {
                    guardrailViolated = true;
                }
            } else {
                double weight = Math.max(0.0, criterion.weight());
                weighted += weight * result.score();
                totalWeight += weight;
            }
            if (!result.passed() || error) {
                String label = (guardrail ? "[guardrail] " : "") + criterion.name() + ": ";
                feedback.add(label + truncate(result.feedback(), feedbackChars));
            }
        }
        double score = guardrailViolated ? 0.0 : totalWeight == 0 ? 1.0 : weighted / totalWeight;
        return new Scorecard(
                scenario.toString(),
                score,
                guardrailViolated,
                outcomes,
                text(output),
                String.join("\n", feedback),
                anyError);
    }

    /** A scorecard for a system that threw instead of producing an output. */
    public static Scorecard systemFailure(EvalScenario scenario, Throwable cause) {
        return new Scorecard(
                scenario.toString(), 0.0, false, List.of(), "", "system threw: " + cause, true);
    }

    static String text(Object output) {
        if (output == null) {
            return "";
        }
        try {
            String text = JudgeCalls.outputText(output);
            return text == null ? "" : text;
        } catch (IllegalArgumentException e) {
            return String.valueOf(output);
        }
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
