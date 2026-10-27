package io.github.llm4j.loom.loop;

import java.math.BigDecimal;

/**
 * What a regress has spent on live models and judges, against the cap it was given. One counter for
 * the whole command, shared by every comparison, so the cap is on the total.
 */
public final class Spent {

    private final Long maxTokens;
    private final BigDecimal maxCost;
    private long tokens;
    private long calls;
    private BigDecimal cost = BigDecimal.ZERO;
    private boolean costKnown = true;

    public Spent(Long maxTokens, BigDecimal maxCost) {
        this.maxTokens = maxTokens;
        this.maxCost = maxCost;
    }

    public synchronized void add(long tokens, long calls, BigDecimal cost) {
        this.tokens += tokens;
        this.calls += calls;
        if (cost == null) costKnown = false;
        else this.cost = this.cost.add(cost);
    }

    public synchronized long tokens() {
        return tokens;
    }

    public synchronized long calls() {
        return calls;
    }

    /** The money spent, or null when some call could not be priced. */
    public synchronized BigDecimal cost() {
        return costKnown ? cost : null;
    }

    public synchronized boolean exhausted() {
        return (maxTokens != null && tokens >= maxTokens)
                || (maxCost != null && costKnown && cost.compareTo(maxCost) >= 0);
    }

    /** Tokens left under the cap, or null for no token cap. */
    public synchronized Long remainingTokens() {
        return maxTokens == null ? null : Math.max(0, maxTokens - tokens);
    }

    /** Money left under the cap, or null for no money cap. */
    public synchronized BigDecimal remainingCost() {
        return maxCost == null ? null : maxCost.subtract(cost).max(BigDecimal.ZERO);
    }

    public Long maxTokens() {
        return maxTokens;
    }

    public BigDecimal maxCost() {
        return maxCost;
    }
}
