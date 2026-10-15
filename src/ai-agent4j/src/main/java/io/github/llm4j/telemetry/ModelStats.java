package io.github.llm4j.telemetry;

import io.github.llm4j.model.LLMResponse;
import java.math.BigDecimal;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * What the calls to one model added up to.
 *
 * @param model the model's name
 * @param calls model calls, failed ones included
 * @param failures failed calls by why
 * @param promptTokens tokens sent
 * @param completionTokens tokens received
 * @param cost the sum of the calls that reported a cost, or null if none did
 * @param retries attempts beyond the first, summed
 * @param failovers clients that failed before one answered, summed
 * @param waitedMillis time spent sleeping between attempts, summed
 * @param finishReasons how the answers ended
 * @param truncated answers cut off by the token limit
 * @param latency how long calls took, failed ones included
 */
public record ModelStats(
        String model,
        long calls,
        Map<ErrorCategory, Long> failures,
        long promptTokens,
        long completionTokens,
        BigDecimal cost,
        long retries,
        long failovers,
        long waitedMillis,
        Map<LLMResponse.FinishReason, Long> finishReasons,
        long truncated,
        LatencyStats latency) {

    public ModelStats {
        failures = Map.copyOf(failures);
        finishReasons = Map.copyOf(finishReasons);
    }

    static ModelStats none(String model) {
        return new ModelStats(model, 0, Map.of(), 0, 0, null, 0, 0, 0, Map.of(), 0, LatencyStats.EMPTY);
    }

    public long failed() {
        return failures.values().stream().mapToLong(Long::longValue).sum();
    }

    /** The share of calls that produced an answer; empty when there were no calls. */
    public OptionalDouble successRate() {
        return calls == 0 ? OptionalDouble.empty() : OptionalDouble.of((calls - failed()) / (double) calls);
    }
}
