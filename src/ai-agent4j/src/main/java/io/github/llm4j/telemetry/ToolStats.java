package io.github.llm4j.telemetry;

import java.util.Map;
import java.util.OptionalDouble;

/**
 * What the calls to one tool added up to.
 *
 * @param tool the tool's name
 * @param calls tool calls, of every outcome
 * @param successes calls that did what was asked
 * @param failures failed calls by why (soft failures included)
 * @param softFailures calls where the tool returned normally but its result was an error
 * @param resultChars size of all results, summed
 * @param latency how long {@code execute} took, over the calls that reached it
 */
public record ToolStats(
        String tool,
        long calls,
        long successes,
        Map<ErrorCategory, Long> failures,
        long softFailures,
        long resultChars,
        LatencyStats latency) {

    public ToolStats {
        failures = Map.copyOf(failures);
    }

    static ToolStats none(String tool) {
        return new ToolStats(tool, 0, 0, Map.of(), 0, 0, LatencyStats.EMPTY);
    }

    public long failed() {
        return calls - successes;
    }

    /** The share of calls that did what was asked; empty when there were no calls. */
    public OptionalDouble successRate() {
        return calls == 0 ? OptionalDouble.empty() : OptionalDouble.of(successes / (double) calls);
    }
}
