package io.github.llm4j.loom.graph;

import io.github.llm4j.loom.ast.BudgetDef;
import java.util.LinkedHashMap;
import java.util.Map;

/** A budget written in a script, as the limits that are set. Unset limits are left out. */
final class BudgetSummary {

    private BudgetSummary() {
    }

    static Map<String, Object> of(BudgetDef budget) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (budget == null) {
            return out;
        }
        put(out, "tokens", budget.getTokens());
        put(out, "calls", budget.getCalls());
        put(out, "cost", budget.getCost() == null ? null : budget.getCost().toPlainString());
        put(out, "perCall", budget.getPerCall());
        put(out, "warnAt", budget.getWarnAt());
        put(out, "window", budget.getWindow() == null ? null : budget.getWindow().name().toLowerCase(java.util.Locale.ROOT));
        put(out, "whenExhausted", budget.getWhenExhausted() == null ? null : budget.getWhenExhausted().name().toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }
}
