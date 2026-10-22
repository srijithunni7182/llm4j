package io.github.llm4j.loom.inspect.state;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The heuristics that say whether a tool is healthy, kept in one place so they can be read, tested
 * and changed together:
 *
 * <ul>
 *   <li>{@code unused}: no call of it has run.
 *   <li>{@code failing}: it has run at least 3 times and never worked; or its last {@code
 *       toolFailures} calls in a row failed; or its success rate over the last 10 calls (at least 5
 *       of them) is below 40 %.
 *   <li>{@code degraded}: that rate is below {@code tool_success_rate} (70 % by default); or it has
 *       run fewer than 5 times and its last call failed.
 *   <li>{@code healthy}: otherwise.
 * </ul>
 *
 * Every verdict has a one-line reason. A call that never ran (blocked, rejected, unknown) is not
 * part of any of this.
 */
final class ToolHealth {

    static final int RECENT = 10;
    static final int NEVER_WORKED_AFTER = 3;
    static final double FAILING_BELOW = 0.4;

    private ToolHealth() {}

    record Verdict(String state, String reason) {}

    static int rank(String state) {
        return switch (state) {
            case "failing" -> 3;
            case "degraded" -> 2;
            case "healthy" -> 1;
            default -> 0;
        };
    }

    static Verdict worse(Verdict a, Verdict b) {
        return rank(a.state()) >= rank(b.state()) ? a : b;
    }

    /**
     * The verdict for one run's account of a tool. {@code recent} is the outcomes of its last calls
     * that ran: empty is a success.
     */
    static Verdict classify(
            long ran,
            long successes,
            long consecutiveFailures,
            List<String> recent,
            String lastFailureCategory,
            Thresholds t) {
        if (ran == 0) return new Verdict("unused", "no call has run yet");
        if (successes == 0 && ran >= NEVER_WORKED_AFTER) {
            return new Verdict(
                    "failing",
                    "never succeeded in "
                            + ran
                            + " calls"
                            + (lastFailureCategory == null
                                    ? ""
                                    : " (last: " + lastFailureCategory + ")"));
        }
        if (consecutiveFailures >= t.toolFailures()) {
            return new Verdict(
                    "failing",
                    "last "
                            + consecutiveFailures
                            + " calls failed"
                            + (lastFailureCategory == null ? "" : ": " + lastFailureCategory));
        }
        if (recent.size() >= Thresholds.MIN_RATE_CALLS) {
            long good = recent.stream().filter(String::isEmpty).count();
            double rate = good / (double) recent.size();
            String counts = good + " of " + recent.size() + " recent calls succeeded";
            if (rate < FAILING_BELOW) return new Verdict("failing", counts);
            if (rate < t.toolRate()) return new Verdict("degraded", counts);
        } else if (consecutiveFailures >= 1) {
            return new Verdict(
                    "degraded",
                    "last call failed"
                            + (lastFailureCategory == null ? "" : ": " + lastFailureCategory));
        }
        return new Verdict(
                "healthy", ran + " call" + (ran == 1 ? "" : "s") + ", " + successes + " succeeded");
    }

    /**
     * What the counts of several runs say together; the parts' own verdicts are weighed separately.
     */
    static Verdict merged(long ran, long successes, Thresholds t) {
        if (ran == 0) return new Verdict("unused", "no call has run yet");
        if (successes == 0 && ran >= NEVER_WORKED_AFTER)
            return new Verdict("failing", "never succeeded in " + ran + " calls");
        double rate = successes / (double) ran;
        if (ran >= Thresholds.MIN_RATE_CALLS && rate < FAILING_BELOW)
            return new Verdict("failing", successes + " of " + ran + " calls succeeded");
        if (ran >= Thresholds.MIN_RATE_CALLS && rate < t.toolRate())
            return new Verdict("degraded", successes + " of " + ran + " calls succeeded");
        return new Verdict("healthy", ran + " calls, " + successes + " succeeded");
    }

    static Map<String, Long> sorted(Map<String, Long> m) {
        return new TreeMap<>(m);
    }
}
