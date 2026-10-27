package io.github.llm4j.loom.generate;

import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.model.LLMResponse;
import java.math.BigDecimal;

/**
 * What a generation has spent and what it may. Checked before each call, so a call is never made
 * that the cap has already been reached for.
 */
public final class Spend {

    private final long maxTokens;
    private final BigDecimal maxCost;
    private final PriceTable.Price price;
    private long tokens;
    private long calls;
    private BigDecimal cost = BigDecimal.ZERO;

    /**
     * @param maxCost the most it may cost, or null; @param price what the writing model costs, or
     *     null when unknown (then only tokens are capped)
     */
    public Spend(long maxTokens, BigDecimal maxCost, PriceTable.Price price) {
        this.maxTokens = maxTokens;
        this.maxCost = maxCost;
        this.price = price;
    }

    /** Thrown by {@link #beforeCall} when the cap has been reached. */
    public static final class CapReached extends RuntimeException {
        public CapReached(String message) {
            super(message, null, true, false);
        }
    }

    public synchronized void beforeCall() {
        if (tokens >= maxTokens)
            throw new CapReached(
                    "The spend cap of " + maxTokens + " tokens is reached (" + tokens + " used).");
        if (maxCost != null && price != null && cost.compareTo(maxCost) >= 0)
            throw new CapReached(
                    "The spend cap of $"
                            + maxCost.toPlainString()
                            + " is reached ($"
                            + cost.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString()
                            + " used).");
    }

    public synchronized void add(LLMResponse r) {
        calls++;
        if (r.getTokenUsage() == null) return;
        long in = r.getTokenUsage().getPromptTokens();
        long out = r.getTokenUsage().getCompletionTokens();
        tokens += in + out;
        if (price != null) cost = cost.add(price.cost(in, out));
    }

    public synchronized long tokens() {
        return tokens;
    }

    public synchronized long calls() {
        return calls;
    }

    /** The cost so far, or null when the price of the model is not known. */
    public synchronized BigDecimal cost() {
        return price == null ? null : cost;
    }
}
