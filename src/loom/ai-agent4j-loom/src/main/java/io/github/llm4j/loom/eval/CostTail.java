package io.github.llm4j.loom.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * The efficiency numbers beyond the average: median and p95 cost of a run, the runaway rate (runs a
 * limit stopped) and the graceful-termination rate (limit stops that still left an output). A
 * system that abandons hard cases cheaply looks good on the median and bad on cost per success, and
 * a few runaway runs dominate the bill, so both are on the same page as quality.
 */
public final class CostTail {

    private static final String STOPPED_BY_LIMIT = "stopped by a limit";
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final double TAIL = 0.95;

    private final List<BigDecimal> sorted;
    private final int runs;
    private final int runaway;
    private final int graceful;

    private CostTail(List<BigDecimal> sorted, int runs, int runaway, int graceful) {
        this.sorted = sorted;
        this.runs = runs;
        this.runaway = runaway;
        this.graceful = graceful;
    }

    /** The tail of the runs of one evaluation. */
    public static CostTail of(List<ScenarioResult> results) {
        List<BigDecimal> costs = new ArrayList<>();
        int runaway = 0;
        int graceful = 0;
        for (ScenarioResult r : results) {
            BigDecimal c = runCost(r);
            if (c != null) costs.add(c);
            if (r.error() != null && r.error().startsWith(STOPPED_BY_LIMIT)) {
                runaway++;
                if (r.output() != null && !r.output().isBlank()) graceful++;
            }
        }
        costs.sort(Comparator.naturalOrder());
        return new CostTail(List.copyOf(costs), results.size(), runaway, graceful);
    }

    /** What the run cost, from its agents' traces, else the run's own figure; null when unknown. */
    private static BigDecimal runCost(ScenarioResult r) {
        BigDecimal sum = null;
        for (AgentFacts a : r.agents())
            if (a.cost() != null) sum = sum == null ? a.cost() : sum.add(a.cost());
        return sum != null ? sum : r.cost();
    }

    /** How many runs had a known cost. */
    public int known() {
        return sorted.size();
    }

    /** All runs, known cost or not. */
    public int runs() {
        return runs;
    }

    /** Runs a limit stopped. */
    public int runaway() {
        return runaway;
    }

    /** Limit stops that still left an output behind. */
    public int graceful() {
        return graceful;
    }

    /** The median cost of a run, null when none is known. */
    public BigDecimal median() {
        if (sorted.isEmpty()) return null;
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(TWO, 6, RoundingMode.HALF_UP);
    }

    /** The cost at the 95th percentile (nearest rank), null when none is known. */
    public BigDecimal p95() {
        if (sorted.isEmpty()) return null;
        int rank = (int) Math.ceil(TAIL * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    /** The report lines; {@code money} formats an amount. */
    public List<String> lines(Function<BigDecimal, String> money) {
        List<String> l = new ArrayList<>();
        if (sorted.isEmpty()) {
            l.add("median per case: unknown without prices; p95 per case: unknown without prices");
        } else {
            l.add(
                    "median per case: "
                            + money.apply(median())
                            + "; p95 per case: "
                            + money.apply(p95())
                            + " (the tail is what a few runaway runs cost)");
        }
        l.add(
                "runaway: "
                        + runaway
                        + " of "
                        + runs
                        + " run"
                        + (runs == 1 ? "" : "s")
                        + " hit a limit"
                        + (runaway == 0
                                ? ""
                                : "; "
                                        + graceful
                                        + " of them kept partial work (graceful), "
                                        + (runaway - graceful)
                                        + " left nothing"));
        return l;
    }
}
