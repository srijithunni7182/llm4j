package io.github.llm4j.loom.execution;

import io.github.llm4j.budget.Charge;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a run's tokens went: every settled LLM call, tagged with the step and agent that made it, and
 * totals per agent and per step. Charges estimated because a provider reported no usage are marked.
 */
public final class SpendReport {

    /** One settled LLM call. */
    public record Line(String step, String agent, String model, Charge charge) { }

    /** Totals for a run, an agent or a step. */
    public record Totals(long promptTokens, long completionTokens, long calls, BigDecimal cost, boolean estimated) {
        static final Totals ZERO = new Totals(0, 0, 0, BigDecimal.ZERO, false);

        public long tokens() {
            return promptTokens + completionTokens;
        }

        Totals plus(Charge c) {
            return new Totals(promptTokens + c.promptTokens(), completionTokens + c.completionTokens(),
                    calls + c.calls(), cost.add(c.cost()), estimated || c.estimated());
        }
    }

    private final List<Line> lines;

    public SpendReport(List<Line> lines) {
        this.lines = List.copyOf(lines);
    }

    public List<Line> lines() {
        return lines;
    }

    public Totals total() {
        Totals t = Totals.ZERO;
        for (Line l : lines) t = t.plus(l.charge());
        return t;
    }

    public Map<String, Totals> byAgent() {
        Map<String, Totals> out = new LinkedHashMap<>();
        for (Line l : lines) out.merge(l.agent(), Totals.ZERO.plus(l.charge()), SpendReport::add);
        return out;
    }

    public Map<String, Totals> byStep() {
        Map<String, Totals> out = new LinkedHashMap<>();
        for (Line l : lines) out.merge(l.step(), Totals.ZERO.plus(l.charge()), SpendReport::add);
        return out;
    }

    /** A plain-text table: one row per agent, then the total. */
    public String table() {
        StringBuilder out = new StringBuilder();
        out.append(String.format("%-24s %7s %10s %12s %12s%n", "agent", "calls", "prompt", "completion", "cost"));
        byAgent().forEach((agent, t) -> out.append(row(agent, t)));
        out.append(row("total", total()));
        if (total().estimated()) out.append("(some usage was estimated: a provider reported none)\n");
        return out.toString();
    }

    private static Totals add(Totals a, Totals b) {
        return new Totals(a.promptTokens() + b.promptTokens(), a.completionTokens() + b.completionTokens(),
                a.calls() + b.calls(), a.cost().add(b.cost()), a.estimated() || b.estimated());
    }

    private static String row(String name, Totals t) {
        return String.format("%-24s %7d %10d %12d %12s%n", name, t.calls(), t.promptTokens(), t.completionTokens(),
                "$" + (t.cost().signum() == 0 ? "0" : t.cost().stripTrailingZeros().toPlainString()));
    }
}
