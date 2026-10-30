package io.github.llm4j.loom.review.report;

import io.github.llm4j.loom.review.cost.CostEstimate;
import io.github.llm4j.loom.review.cost.StepCost;
import io.github.llm4j.loom.review.cost.Usage;
import io.github.llm4j.loom.review.cost.WorkflowCost;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code cost} block of the JSON report: per workflow and per step, tokens and money, with an
 * unbounded worst case written as {@code "unbounded": true} and never as a number.
 */
final class CostJson {

    private CostJson() {}

    static Map<String, Object> of(CostEstimate c) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> workflows = new ArrayList<>();
        for (WorkflowCost w : c.workflows()) workflows.add(workflow(c, w));
        root.put("workflows", workflows);
        root.put("assumptions", c.assumptions());
        root.put("prices", c.priceLabel());
        root.put("unpriced", c.unpriced());
        root.put("estimate", true);
        return root;
    }

    private static Map<String, Object> workflow(CostEstimate c, WorkflowCost w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", w.name());
        m.put("typical", usage(c, w.typical(), false));
        Map<String, Object> worst = usage(c, w.worst(), w.unbounded());
        if (w.unbounded()) worst.put("atLeast", true);
        if (c.budgetCost() != null) {
            worst.put("budget", c.budgetCost());
            worst.put("cappedByBudget", c.capped(w));
            if (c.cappedWorst(w) != null) worst.put("cappedMoney", c.cappedWorst(w));
        }
        m.put("worst", worst);
        if (c.budgetTokens() != null) {
            m.put("tokenCap", c.budgetTokens());
            m.put("capStopsBeforeWorstCase", c.tokenCapStopsFirst(w));
        }
        List<Object> steps = new ArrayList<>();
        for (StepCost s : w.steps()) steps.add(step(c, s));
        m.put("steps", steps);
        m.put("notes", w.notes());
        if (w.time() != null) m.put("time", time(w.time()));
        if (w.effects() != null) m.put("effects", effects(w.effects()));
        return m;
    }

    private static Map<String, Object> time(io.github.llm4j.loom.review.cost.TimeEstimate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("typicalMs", t.typicalMillis());
        Map<String, Object> worst = new LinkedHashMap<>();
        worst.put("ms", t.worstMillis());
        if (t.unbounded()) worst.put("atLeast", true);
        m.put("worst", worst);
        m.put("criticalPath", t.criticalPath());
        m.put("waits", t.waits());
        m.put("sleepMs", t.sleepMillis());
        m.put("assumptions", t.assumptions());
        return m;
    }

    private static Map<String, Object> effects(io.github.llm4j.loom.review.cost.EffectsEstimate e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sends", count(e.sends()));
        m.put("writes", count(e.writes()));
        return m;
    }

    private static Map<String, Object> count(
            io.github.llm4j.loom.review.cost.EffectsEstimate.Count c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("typical", c.typical());
        m.put("worst", c.unbounded() ? null : c.worst());
        if (c.unbounded()) {
            m.put("unbounded", true);
            m.put("atLeast", c.worst());
        }
        return m;
    }

    private static Map<String, Object> step(CostEstimate c, StepCost s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", s.line());
        m.put("label", s.label());
        m.put("model", s.model());
        m.put("typicalRuns", s.typicalRuns());
        m.put("worstRuns", s.worstRuns() < 0 ? null : s.worstRuns());
        m.put("typical", usage(c, s.typical(), false));
        m.put("worst", s.worst() == null ? null : usage(c, s.worst(), false));
        return m;
    }

    private static Map<String, Object> usage(CostEstimate c, Usage u, boolean unbounded) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calls", u.calls());
        m.put("tokensIn", u.tokensIn());
        m.put("tokensOut", u.tokensOut());
        Usage.Money money = u.money(c.prices());
        m.put("money", money.complete() ? money.amount() : null);
        m.put("unpriced", money.unpriced());
        if (unbounded) m.put("unbounded", true);
        return m;
    }
}
