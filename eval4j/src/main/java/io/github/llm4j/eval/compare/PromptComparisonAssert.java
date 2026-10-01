package io.github.llm4j.eval.compare;

import java.util.List;
import java.util.Locale;
import org.assertj.core.api.AbstractAssert;

/** AssertJ assertions over a {@link PromptComparison.Result}. */
public class PromptComparisonAssert
        extends AbstractAssert<PromptComparisonAssert, PromptComparison.Result> {

    private static final int WORST = 3;

    public PromptComparisonAssert(PromptComparison.Result actual) {
        super(actual, PromptComparisonAssert.class);
    }

    /** The candidate (B) wins at least {@code minimum} (0-1) of all scenarios. */
    public PromptComparisonAssert candidateWinRateAtLeast(double minimum) {
        isNotNull();
        if (actual.winRateB() < minimum) {
            failWithMessage(
                    "Expected %s to win at least %.0f%% of scenarios but won %.0f%%.%n%s%n%s",
                    actual.nameB(), 100 * minimum, 100 * actual.winRateB(), summary(), worst());
        }
        return this;
    }

    /** The candidate loses no more than {@code tolerance} more often than it wins. */
    public PromptComparisonAssert doesNotRegress(double tolerance) {
        isNotNull();
        double gap = actual.winRateA() - actual.winRateB();
        if (gap > tolerance) {
            failWithMessage(
                    "Expected %s not to regress against %s (tolerance %.0f%%) but %s wins %.0f%% and %s"
                            + " wins %.0f%%.%n%s%n%s",
                    actual.nameB(),
                    actual.nameA(),
                    100 * tolerance,
                    actual.nameA(),
                    100 * actual.winRateA(),
                    actual.nameB(),
                    100 * actual.winRateB(),
                    summary(),
                    worst());
        }
        return this;
    }

    /** Same as {@link #doesNotRegress(double)}, checking the variant name is the candidate. */
    public PromptComparisonAssert doesNotRegress(String candidateName, double tolerance) {
        isNotNull();
        if (!actual.nameB().equals(candidateName)) {
            failWithMessage(
                    "Expected candidate variant to be named <%s> but it was <%s>",
                    candidateName, actual.nameB());
        }
        return doesNotRegress(tolerance);
    }

    public PromptComparisonAssert hasNoErrors() {
        isNotNull();
        if (actual.errors() > 0) {
            List<String> failing =
                    actual.outcomes().stream()
                            .filter(o -> o.outcome().name().startsWith("ERROR"))
                            .map(o -> o.scenario() + ": " + o.reason())
                            .toList();
            failWithMessage(
                    "Expected no variant errors but had %d:%n  %s",
                    actual.errors(), String.join("\n  ", failing));
        }
        return this;
    }

    private String summary() {
        String interval =
                actual.candidateWinRateInterval()
                        .map(
                                i ->
                                        String.format(
                                                Locale.ROOT,
                                                " (95%% CI for %s's share of decisive results: %.2f-%.2f)",
                                                actual.nameB(),
                                                i.low(),
                                                i.high()))
                        .orElse("");
        return actual + interval;
    }

    /** Reasons for up to three scenarios the candidate lost. */
    private String worst() {
        List<String> lost =
                actual.outcomes().stream()
                        .filter(
                                o ->
                                        o.outcome() == PromptComparison.Outcome.A_WINS
                                                || o.outcome() == PromptComparison.Outcome.ERROR_B)
                        .limit(WORST)
                        .map(o -> "  - " + o.scenario() + ": " + o.reason())
                        .toList();
        return lost.isEmpty()
                ? "No scenarios lost by the candidate."
                : "Scenarios the candidate lost:\n" + String.join("\n", lost);
    }
}
