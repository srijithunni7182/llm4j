package io.github.llm4j.budget;

import java.math.BigDecimal;

/**
 * What a budget has spent. {@code estimated} is true if any charge was estimated because the provider
 * reported no usage; {@code overdraw} is how far settled tokens went past the token limit (a provider
 * that ignored {@code maxTokens}, or a prompt estimate that was too low).
 */
public record Spent(long promptTokens, long completionTokens, long calls, BigDecimal cost, boolean estimated,
                    long overdraw) {

    public static final Spent NONE = new Spent(0, 0, 0, BigDecimal.ZERO, false, 0);

    public long tokens() {
        return promptTokens + completionTokens;
    }

    /** The sum of two spends (overdraw is not additive and is dropped). */
    public Spent plus(Spent other) {
        return new Spent(promptTokens + other.promptTokens, completionTokens + other.completionTokens,
                calls + other.calls, cost.add(other.cost), estimated || other.estimated, 0);
    }

    public static Spent of(Charge charge) {
        return new Spent(charge.promptTokens(), charge.completionTokens(), charge.calls(),
                charge.cost() == null ? BigDecimal.ZERO : charge.cost(), charge.estimated(), 0);
    }
}
