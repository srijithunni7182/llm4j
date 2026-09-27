package io.github.llm4j.budget;

import io.github.llm4j.agent.AgentInterrupt;

/**
 * A call was refused because it would exceed a budget. It is an {@link AgentInterrupt}, so it is never
 * turned into a tool error and never retried: retrying would only spend more.
 */
public final class BudgetExceeded extends AgentInterrupt {

    private final String budget;
    private final Dimension dimension;
    private final Spent spent;
    private final Limits limits;
    private final java.time.Instant resetAt;

    public BudgetExceeded(String budget, Dimension dimension, Spent spent, Limits limits) {
        this(budget, dimension, spent, limits, null);
    }

    /** @param resetAt when a windowed budget refills, or null for a lifetime budget */
    public BudgetExceeded(String budget, Dimension dimension, Spent spent, Limits limits, java.time.Instant resetAt) {
        super(message(budget, dimension, spent, limits) + (resetAt == null ? "" : "; refills at " + resetAt));
        this.budget = budget;
        this.dimension = dimension;
        this.spent = spent;
        this.limits = limits;
        this.resetAt = resetAt;
    }

    /** When the refusing budget's window rolls over and it refills; empty for a lifetime budget. */
    public java.util.Optional<java.time.Instant> resetAt() {
        return java.util.Optional.ofNullable(resetAt);
    }

    private static String message(String budget, Dimension dimension, Spent spent, Limits limits) {
        String detail = switch (dimension) {
            case TOKENS -> "tokens " + spent.tokens() + "/" + limits.tokens();
            case CALLS -> "calls " + spent.calls() + "/" + limits.calls();
            case COST -> "cost $" + money(spent.cost()) + "/$" + money(limits.cost());
        };
        return "budget exhausted: " + budget + " (" + detail + ")";
    }

    private static String money(java.math.BigDecimal amount) {
        return amount.signum() == 0 ? "0" : amount.stripTrailingZeros().toPlainString();
    }

    /** The name of the budget that refused, e.g. {@code run}, {@code agent Writer}, {@code step Main/3}. */
    public String budget() {
        return budget;
    }

    public Dimension dimension() {
        return dimension;
    }

    public Spent spent() {
        return spent;
    }

    public Limits limits() {
        return limits;
    }
}
