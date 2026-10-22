package io.github.llm4j.grounding;

import java.util.List;

/**
 * The web searches a call ran, as the vendor reports them.
 *
 * @param count how many searches ran, in the vendor's own unit (an invocation, a call item); null
 *     when the vendor does not say, because a number that was guessed is worse than none
 * @param queries the search queries, when the vendor reports them (empty otherwise). Text: shown
 *     only where the detail level allows text
 * @param estimatedCostUsd what the searches cost: the vendor's own figure when it reports one, else
 *     the user's price table; null when unknown
 */
public record SearchUsage(Integer count, List<String> queries, Double estimatedCostUsd) {

    public SearchUsage {
        if (count != null && count < 0)
            throw new IllegalArgumentException("count cannot be negative, got " + count);
        if (estimatedCostUsd != null && estimatedCostUsd < 0)
            throw new IllegalArgumentException("cost cannot be negative, got " + estimatedCostUsd);
        queries = queries == null ? List.of() : List.copyOf(queries);
    }

    /** {@code count} searches, nothing else known. */
    public static SearchUsage of(int count) {
        return new SearchUsage(count, List.of(), null);
    }

    /** Whether the vendor said how many searches ran. */
    public boolean countKnown() {
        return count != null;
    }

    /** The same usage with a cost. */
    public SearchUsage withEstimatedCost(Double usd) {
        return new SearchUsage(count, queries, usd);
    }
}
