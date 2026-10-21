package io.github.llm4j.context;

import java.math.BigDecimal;

/**
 * What a strategy spent on model calls of its own (a summary, for instance).
 *
 * @param calls model calls made
 * @param promptTokens tokens sent
 * @param completionTokens tokens received
 * @param cost the cost, or null when unpriced
 */
public record CompactionUsage(
        int calls, long promptTokens, long completionTokens, BigDecimal cost) {

    /** The two added up; either may be null. */
    public static CompactionUsage sum(CompactionUsage a, CompactionUsage b) {
        if (a == null) return b;
        if (b == null) return a;
        BigDecimal cost = a.cost == null ? b.cost : b.cost == null ? a.cost : a.cost.add(b.cost);
        return new CompactionUsage(
                a.calls + b.calls,
                a.promptTokens + b.promptTokens,
                a.completionTokens + b.completionTokens,
                cost);
    }
}
