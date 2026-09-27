package io.github.llm4j.budget;

import java.math.BigDecimal;

/** One settled LLM call: its tokens, the call itself, its cost (zero if unpriced) and whether it was estimated. */
public record Charge(long promptTokens, long completionTokens, int calls, BigDecimal cost, boolean estimated) {

    public Charge {
        if (cost == null) cost = BigDecimal.ZERO;
    }

    public long tokens() {
        return promptTokens + completionTokens;
    }
}
