package io.github.llm4j.budget;

import java.math.BigDecimal;

/** A budget's limits. A {@code null} limit means that dimension is unlimited. */
public record Limits(Long tokens, Long calls, BigDecimal cost) {

    public static final Limits NONE = new Limits(null, null, null);

    public boolean any() {
        return tokens != null || calls != null || cost != null;
    }

    /** Output tokens change what a call spends only when tokens or cost are limited. */
    boolean constrainsOutput() {
        return tokens != null || cost != null;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        if (tokens != null) s.append("tokens ").append(tokens);
        if (calls != null) s.append(s.isEmpty() ? "" : ", ").append("calls ").append(calls);
        if (cost != null) s.append(s.isEmpty() ? "" : ", ").append("cost $").append(cost.toPlainString());
        return s.isEmpty() ? "unlimited" : s.toString();
    }
}
