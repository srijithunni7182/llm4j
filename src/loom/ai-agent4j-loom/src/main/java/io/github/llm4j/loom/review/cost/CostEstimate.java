package io.github.llm4j.loom.review.cost;

import io.github.llm4j.budget.PriceTable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The cost of every workflow of a script, with the assumptions it rests on and the script's budget,
 * so a report can show tokens, money, and what the budget caps.
 *
 * @param workflows one entry per workflow
 * @param assumptions the assumptions used, with their values
 * @param prices the price table, or null when none was given
 * @param priceLabel where the prices came from, for the report footer
 * @param budgetCost the script's cost budget, or null
 * @param budgetTokens the script's token budget, or null
 */
public record CostEstimate(
        List<WorkflowCost> workflows,
        Map<String, Long> assumptions,
        PriceTable prices,
        String priceLabel,
        BigDecimal budgetCost,
        Long budgetTokens) {

    public CostEstimate {
        workflows = List.copyOf(workflows);
        assumptions = Map.copyOf(assumptions);
    }

    public Usage.Money typicalMoney(WorkflowCost w) {
        return w.typical().money(prices);
    }

    public Usage.Money worstMoney(WorkflowCost w) {
        return w.worst().money(prices);
    }

    /**
     * The worst case money after the script's budget: the smaller of the computed figure and the
     * budget, or the budget alone when the computed one is unbounded. Null when there is neither a
     * bound nor a budget.
     */
    public BigDecimal cappedWorst(WorkflowCost w) {
        Usage.Money m = worstMoney(w);
        if (budgetCost == null) return w.unbounded() || !m.complete() ? null : m.amount();
        if (w.unbounded() || !m.complete()) return budgetCost;
        return m.amount().min(budgetCost);
    }

    /** True when the budget (not the workflow) decides the worst case. */
    public boolean capped(WorkflowCost w) {
        if (budgetCost == null) return false;
        Usage.Money m = worstMoney(w);
        return w.unbounded() || (m.complete() && m.amount().compareTo(budgetCost) > 0);
    }

    /**
     * True when the script's token budget is at or below the worst case (or the worst case has no
     * bound): the run stops at the cap before it could reach it, so results may be cut.
     */
    public boolean tokenCapStopsFirst(WorkflowCost w) {
        return budgetTokens != null && (w.unbounded() || w.worst().tokens() >= budgetTokens);
    }

    /** Models used by some workflow that have no price. */
    public List<String> unpriced() {
        return workflows.stream()
                .flatMap(
                        w ->
                                java.util.stream.Stream.concat(
                                        w.worst().money(prices).unpriced().stream(),
                                        w.typical().money(prices).unpriced().stream()))
                .distinct()
                .sorted()
                .toList();
    }
}
