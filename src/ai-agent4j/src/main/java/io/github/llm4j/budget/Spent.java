package io.github.llm4j.budget;

import java.math.BigDecimal;

/**
 * What a budget has spent. {@code estimated} is true if any charge was estimated because the
 * provider reported no usage; {@code overdraw} is how far settled tokens went past the token limit
 * (a provider that ignored {@code maxTokens}, or a prompt estimate that was too low); {@code
 * searches} is the provider-side web searches run, and {@code searchOverdraw} how far they went
 * past the search limit (a provider that ignored the cap).
 */
public record Spent(
        long promptTokens,
        long completionTokens,
        long calls,
        BigDecimal cost,
        boolean estimated,
        long overdraw,
        long searches,
        long searchOverdraw) {

    public static final Spent NONE = new Spent(0, 0, 0, BigDecimal.ZERO, false, 0);

    /** A spend with no searches (the shape before grounding existed). */
    public Spent(
            long promptTokens,
            long completionTokens,
            long calls,
            BigDecimal cost,
            boolean estimated,
            long overdraw) {
        this(promptTokens, completionTokens, calls, cost, estimated, overdraw, 0, 0);
    }

    /** A spend with searches and no search overdraw. */
    public Spent(
            long promptTokens,
            long completionTokens,
            long calls,
            BigDecimal cost,
            boolean estimated,
            long overdraw,
            long searches) {
        this(promptTokens, completionTokens, calls, cost, estimated, overdraw, searches, 0);
    }

    public long tokens() {
        return promptTokens + completionTokens;
    }

    /** The sum of two spends (overdraw is not additive and is dropped). */
    public Spent plus(Spent other) {
        return new Spent(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                calls + other.calls,
                cost.add(other.cost),
                estimated || other.estimated,
                0,
                searches + other.searches,
                0);
    }

    public static Spent of(Charge charge) {
        return new Spent(
                charge.promptTokens(),
                charge.completionTokens(),
                charge.calls(),
                charge.cost() == null ? BigDecimal.ZERO : charge.cost(),
                charge.estimated(),
                0,
                charge.searches(),
                0);
    }
}
