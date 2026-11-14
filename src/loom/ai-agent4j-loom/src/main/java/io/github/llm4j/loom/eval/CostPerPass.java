package io.github.llm4j.loom.eval;

import io.github.llm4j.loom.autonomy.expr.Wilson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What one passing case cost (phase 35, D17): the run's cost over its passes, with a 95% interval
 * from the Wilson interval of the pass rate. A cheap run that rarely passes is not cheap: the figure
 * puts the two side by side.
 */
public final class CostPerPass {

    private CostPerPass() {}

    /** The figure, low and high in dollars (high is null when nothing may pass), or null when unknown. */
    public record Figure(BigDecimal value, BigDecimal low, BigDecimal high, long passed, long judged) {}

    /** The figure for a run that cost {@code total} and passed {@code passed} of {@code judged} cases. */
    public static Figure of(BigDecimal total, long passed, long judged) {
        if (total == null || total.signum() <= 0 || judged <= 0) return null;
        BigDecimal perCase = total.divide(BigDecimal.valueOf(judged), 10, RoundingMode.HALF_UP);
        double lower = Wilson.lower((int) passed, (int) judged);
        double upper = Wilson.upper((int) passed, (int) judged);
        BigDecimal value = passed == 0 ? null : total.divide(BigDecimal.valueOf(passed), 10, RoundingMode.HALF_UP);
        BigDecimal low = upper <= 0 ? null : perCase.divide(BigDecimal.valueOf(upper), 10, RoundingMode.HALF_UP);
        BigDecimal high = lower <= 0 ? null : perCase.divide(BigDecimal.valueOf(lower), 10, RoundingMode.HALF_UP);
        return new Figure(value, low, high, passed, judged);
    }

    /**
     * The interval {@code weave eval} prints after the cost per passing case, e.g. {@code " (95%
     * interval $0.0120 to $0.0310, from 7 of 10 judged)"}; empty when nothing was spent or judged.
     */
    public static String interval(BigDecimal total, long passed, long judged) {
        Figure f = of(total, passed, judged);
        if (f == null || f.value() == null) return "";
        return " (95% interval " + money(f.low()) + " to " + (f.high() == null ? "no upper bound" : money(f.high()))
                + ", from " + passed + " of " + judged + " judged)";
    }

    /** The figure as the JSON export carries it, or null. */
    public static Map<String, Object> json(BigDecimal total, long passed, long judged) {
        Figure f = of(total, passed, judged);
        if (f == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", f.value() == null ? null : plain(f.value()));
        m.put("low", f.low() == null ? null : plain(f.low()));
        m.put("high", f.high() == null ? null : plain(f.high()));
        m.put("passed", passed);
        m.put("judged", judged);
        return m;
    }

    private static String plain(BigDecimal d) {
        return d.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal d) {
        return String.format(Locale.ROOT, "$%.4f", d);
    }
}
