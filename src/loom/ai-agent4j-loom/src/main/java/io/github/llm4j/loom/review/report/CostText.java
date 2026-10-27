package io.github.llm4j.loom.review.report;

import io.github.llm4j.loom.review.cost.CostEstimate;
import io.github.llm4j.loom.review.cost.EffectsEstimate;
import io.github.llm4j.loom.review.cost.TimeEstimate;
import io.github.llm4j.loom.review.cost.Usage;
import io.github.llm4j.loom.review.cost.WorkflowCost;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cost figures as short sentences for the text and Markdown reports. */
final class CostText {

    private CostText() {}

    /** One line per workflow: typical and worst case, with the budget's part in it. */
    static List<String> lines(CostEstimate c) {
        List<String> out = new ArrayList<>();
        for (WorkflowCost w : c.workflows()) {
            if (w.typical().isEmpty() && w.worst().isEmpty() && !w.unbounded()) continue;
            out.add(w.name() + ": typical " + typical(c, w) + "   worst case " + worst(c, w));
            String more = timeAndEffects(w);
            if (!more.isEmpty()) out.add("  " + w.name() + ": " + more);
        }
        if (out.isEmpty()) out.add("no model calls");
        return out;
    }

    /** {@code time typical 6 s, worst 41 s (+ waits: 1 person) effects worst 1 send}, or empty. */
    static String timeAndEffects(WorkflowCost w) {
        List<String> parts = new ArrayList<>();
        TimeEstimate t = w.time();
        if (t != null && (t.worstMillis() > 0 || t.unbounded() || !t.waits().isEmpty())) {
            StringBuilder b =
                    new StringBuilder("time typical ")
                            .append(seconds(t.typicalMillis()))
                            .append(", worst ")
                            .append(
                                    t.unbounded()
                                            ? "unbounded ("
                                                    + seconds(t.worstMillis())
                                                    + " at least)"
                                            : seconds(t.worstMillis()));
            if (!t.waits().isEmpty())
                b.append(" (+ waits: ")
                        .append(t.waits().size())
                        .append(" stop")
                        .append(t.waits().size() == 1 ? "" : "s")
                        .append(")");
            parts.add(b.toString());
        }
        EffectsEstimate e = w.effects();
        if (e != null && !e.none()) {
            List<String> kinds = new ArrayList<>();
            if (!e.sends().none()) kinds.add(count(e.sends(), "send"));
            if (!e.writes().none()) kinds.add(count(e.writes(), "write"));
            parts.add("effects worst " + String.join(", ", kinds));
        }
        return String.join("   ", parts);
    }

    private static String count(EffectsEstimate.Count c, String noun) {
        if (c.unbounded()) return "unbounded " + noun + "s";
        return c.worst()
                + " "
                + noun
                + (c.worst() == 1 ? "" : "s")
                + " (typically "
                + c.typical()
                + ")";
    }

    static String seconds(long millis) {
        if (millis < 1000) return millis + " ms";
        long s = Math.round(millis / 1000.0);
        if (s < 120) return s + " s";
        if (s < 7200) return (s / 60) + " min";
        return (s / 3600) + " h";
    }

    static String typical(CostEstimate c, WorkflowCost w) {
        return amount(c.typicalMoney(w), w.typical()) + " / run";
    }

    static String worst(CostEstimate c, WorkflowCost w) {
        if (c.capped(w) && c.budgetCost() != null) {
            String why = w.unbounded() ? "unbounded" : money(c.worstMoney(w).amount());
            return money(c.budgetCost()) + " (capped by the budget; computed: " + why + ")";
        }
        if (w.unbounded()) return "unbounded (" + tokens(w.worst()) + " at least)";
        return amount(c.worstMoney(w), w.worst());
    }

    /** Money when every model is priced, else tokens (and which are unpriced). */
    static String amount(Usage.Money m, Usage u) {
        if (m.complete() && !u.isEmpty()) return money(m.amount());
        if (u.isEmpty()) return "$0";
        return tokens(u) + " (no price for " + String.join(", ", m.unpriced()) + ")";
    }

    static String tokens(Usage u) {
        return String.format(Locale.ROOT, "%,d tokens", u.tokens());
    }

    static String money(BigDecimal v) {
        BigDecimal r =
                v.compareTo(new BigDecimal("0.01")) < 0
                        ? v.setScale(4, RoundingMode.HALF_UP)
                        : v.setScale(2, RoundingMode.HALF_UP);
        return "$" + r.toPlainString();
    }

    /** What the figures rest on, for the report footer. */
    static String footer(CostEstimate c) {
        StringBuilder b = new StringBuilder("These are estimates from the script alone.");
        if (c.priceLabel() != null) b.append(" prices: ").append(c.priceLabel()).append('.');
        else b.append(" No price table (--prices FILE): tokens only.");
        if (!c.assumptions().isEmpty()) {
            b.append(" Assumed: ");
            List<String> parts = new ArrayList<>();
            c.assumptions().forEach((k, v) -> parts.add(k.substring("assume.".length()) + "=" + v));
            b.append(String.join(", ", parts)).append('.');
        }
        return b.toString();
    }
}
