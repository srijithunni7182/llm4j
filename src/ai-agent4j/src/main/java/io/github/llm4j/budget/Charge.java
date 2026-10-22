package io.github.llm4j.budget;

import java.math.BigDecimal;

/**
 * One settled LLM call: its tokens, the call itself, its cost (zero if unpriced), the provider-side
 * searches it ran and whether it was estimated.
 */
public record Charge(
        long promptTokens,
        long completionTokens,
        int calls,
        BigDecimal cost,
        boolean estimated,
        long searches) {

    public Charge {
        if (cost == null) cost = BigDecimal.ZERO;
    }

    /** A call that ran no searches (the shape before grounding existed). */
    public Charge(
            long promptTokens,
            long completionTokens,
            int calls,
            BigDecimal cost,
            boolean estimated) {
        this(promptTokens, completionTokens, calls, cost, estimated, 0);
    }

    public long tokens() {
        return promptTokens + completionTokens;
    }
}
