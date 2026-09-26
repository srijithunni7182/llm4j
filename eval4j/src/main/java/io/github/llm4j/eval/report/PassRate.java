package io.github.llm4j.eval.report;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates outcomes across many eval cases and asserts on the overall pass <em>rate</em>
 * instead of any single case. Judge-based metrics are noisy enough that requiring every one of,
 * say, 50 dataset cases to pass is usually the wrong bar — a suite is healthy if it clears an
 * agreed threshold, not if every last borderline case agrees with the judge.
 *
 * <pre>{@code
 * PassRate passRate = new PassRate();
 * for (Scenario scenario : scenarios) {
 *     AgentResult result = agent.run(scenario.input());
 *     passRate.record(() -> assertThat(result).hasFinalAnswerContaining(scenario.expected()));
 * }
 * passRate.requireAtLeast(0.9);
 * }</pre>
 *
 * <p>{@link #record(Runnable)} works with any assertion that throws a plain {@link
 * AssertionError} — eval4j's fluent assertions, AssertJ, or vanilla JUnit — so this class has no
 * dependency on the rest of eval4j.
 */
public final class PassRate {

    private int total;
    private int passed;
    private final List<String> failureMessages = new ArrayList<>();

    /**
     * Runs {@code assertion}, counting it as passed if it completes normally and as failed
     * (without propagating) if it throws an {@link AssertionError}.
     */
    public void record(Runnable assertion) {
        total++;
        try {
            assertion.run();
            passed++;
        } catch (AssertionError e) {
            failureMessages.add(e.getMessage());
        }
    }

    public int passedCount() {
        return passed;
    }

    public int totalCount() {
        return total;
    }

    public double rate() {
        return total == 0 ? 0.0 : (double) passed / total;
    }

    /** Throws an {@link AssertionError} if the pass rate so far is below {@code minRate}. */
    public void requireAtLeast(double minRate) {
        if (rate() < minRate) {
            throw new AssertionError(
                    String.format(
                            "Expected pass rate >= %.2f but was %.2f (%d/%d passed)%s",
                            minRate, rate(), passed, total, formatFailures()));
        }
    }

    private String formatFailures() {
        if (failureMessages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nFailures:");
        for (String message : failureMessages) {
            sb.append("\n  - ").append(message);
        }
        return sb.toString();
    }
}
