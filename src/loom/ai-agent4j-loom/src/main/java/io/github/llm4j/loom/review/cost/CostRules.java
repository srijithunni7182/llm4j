package io.github.llm4j.loom.review.cost;

import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * The cost rules that need the estimate itself and so cannot be written as data over facts: a
 * budget below the typical cost (LC04), one step that is most of a workflow's cost (LC05), and a
 * retried step whose worst case is expensive (LC06). The rest of the cost lens is in the core pack.
 *
 * <p>Settings: {@code cost.step.share} is the percentage that makes one step too large a part of a
 * workflow (default 60); {@code cost.retry.limit} is the worst-case amount for a retried step above
 * which LC06 speaks, in the currency of the price table (default 1.00).
 */
public final class CostRules implements FindingSource {

    private static final long DEFAULT_SHARE = 60;
    private static final BigDecimal DEFAULT_RETRY_LIMIT = BigDecimal.ONE;

    private final CostEstimator estimator;

    public CostRules(CostEstimator estimator) {
        this.estimator = estimator;
    }

    /** What is known about the rules in this class, for {@code --explain}. */
    public static List<io.github.llm4j.loom.review.engine.RuleInfo> catalog() {
        return List.of(
                new io.github.llm4j.loom.review.engine.RuleInfo(
                        "LC04",
                        Lens.COST,
                        Severity.MEDIUM,
                        "The budget is below what a normal run costs",
                        "A budget stops or pauses a run when it is spent. If the budget is smaller than the typical cost of the workflow, most runs are cut off. The estimate is from the script alone (weave review --explain cost). What to do: raise the budget above the typical cost or make the workflow cheaper.",
                        "cost"),
                new io.github.llm4j.loom.review.engine.RuleInfo(
                        "LC05",
                        Lens.COST,
                        Severity.INFO,
                        "One step is most of a workflow's cost",
                        "When a single step is more than cost.step.share percent (default 60) of the typical cost of its workflow, any saving has to come from that step. What to do: look at its model, prompt length and rounds first.",
                        "cost"),
                new io.github.llm4j.loom.review.engine.RuleInfo(
                        "LC06",
                        Lens.COST,
                        Severity.LOW,
                        "A retried step can be expensive in the worst case",
                        "Each retry pays for the whole call again. When the worst case of a retried step is above cost.retry.limit (default 1.00, in the currency of your price table) the finding is raised. It needs a price table (--prices). What to do: retry fewer times or make the step cheaper.",
                        "cost"));
    }

    @Override
    public String name() {
        return "cost rules";
    }

    @Override
    public void collect(ReviewInput input, ReviewSettings settings, Sink sink) {
        if (!settings.lensOn(Lens.COST)) return;
        CostEstimate c = estimator.estimate(input, settings);
        long share = number(settings, "cost.step.share", DEFAULT_SHARE);
        BigDecimal retryLimit = decimal(settings, "cost.retry.limit", DEFAULT_RETRY_LIMIT);
        for (WorkflowCost w : c.workflows()) {
            budget(c, w, input, sink);
            dominantStep(c, w, input, share, sink);
            retries(c, w, input, retryLimit, sink);
        }
    }

    private static void budget(CostEstimate c, WorkflowCost w, ReviewInput in, Sink sink) {
        Usage.Money typical = c.typicalMoney(w);
        if (c.budgetCost() != null
                && typical.complete()
                && typical.amount().compareTo(c.budgetCost()) > 0)
            sink.finding(
                    finding(
                            "LC04",
                            Severity.MEDIUM,
                            Location.at(in.file(), 0),
                            "Workflow "
                                    + w.name()
                                    + " typically costs "
                                    + money(typical.amount())
                                    + " but the budget is "
                                    + money(c.budgetCost())
                                    + ", so an ordinary run is cut off",
                            "When the budget runs out the run stops or asks, and with a budget below the normal cost that happens on most runs.",
                            "Raise the budget above the typical cost, or make the workflow cheaper."));
        else if (c.budgetTokens() != null && w.typical().tokens() > c.budgetTokens())
            sink.finding(
                    finding(
                            "LC04",
                            Severity.MEDIUM,
                            Location.at(in.file(), 0),
                            "Workflow "
                                    + w.name()
                                    + " typically uses about "
                                    + w.typical().tokens()
                                    + " tokens but the budget is "
                                    + c.budgetTokens()
                                    + ", so an ordinary run is cut off",
                            "When the budget runs out the run stops or asks, and with a budget below the normal use that happens on most runs.",
                            "Raise the token budget above the typical use, or make the workflow cheaper."));
    }

    private static void dominantStep(
            CostEstimate c, WorkflowCost w, ReviewInput in, long share, Sink sink) {
        if (w.steps().size() < 2) return;
        boolean priced = c.typicalMoney(w).complete();
        BigDecimal total = weight(c, w.typical(), priced);
        if (total.signum() == 0) return;
        for (StepCost s : w.steps()) {
            BigDecimal part = weight(c, s.typical(), priced);
            long pct =
                    part.multiply(BigDecimal.valueOf(100))
                            .divide(total, 0, RoundingMode.HALF_UP)
                            .longValue();
            if (pct > share)
                sink.finding(
                        finding(
                                "LC05",
                                Severity.INFO,
                                Location.at(in.file(), s.line()),
                                s.label()
                                        + " is about "
                                        + pct
                                        + " % of the typical cost of "
                                        + w.name(),
                                "One step that dominates the cost is where any saving has to come from.",
                                "Look at this step first: a smaller model, a shorter prompt or fewer rounds."));
        }
    }

    private static void retries(
            CostEstimate c, WorkflowCost w, ReviewInput in, BigDecimal limit, Sink sink) {
        for (StepCost s : w.steps()) {
            if (s.retries() <= 0 || s.worst() == null) continue;
            Usage.Money m = s.worst().money(c.prices());
            if (m.complete() && m.amount().compareTo(limit) > 0)
                sink.finding(
                        finding(
                                "LC06",
                                Severity.LOW,
                                Location.at(in.file(), s.line()),
                                s.label()
                                        + " retries "
                                        + s.retries()
                                        + " times and can cost up to "
                                        + money(m.amount())
                                        + " in "
                                        + w.name(),
                                "Every retry pays for the whole call again, so an expensive step multiplies its cost when it fails.",
                                "Retry fewer times, or put a cheaper step in front, or lower cost.retry.limit if this is expected."));
        }
    }

    private static BigDecimal weight(CostEstimate c, Usage u, boolean priced) {
        return priced ? u.money(c.prices()).amount() : BigDecimal.valueOf(u.tokens());
    }

    private static Finding finding(
            String rule, Severity sev, Location where, String title, String why, String fix) {
        return new Finding(
                rule,
                Lens.COST,
                sev,
                where,
                title,
                why,
                fix,
                List.of(),
                Optional.empty(),
                List.of(),
                "cost");
    }

    private static String money(BigDecimal v) {
        return "$"
                + (v.compareTo(new BigDecimal("0.01")) < 0
                                ? v.setScale(4, RoundingMode.HALF_UP)
                                : v.setScale(2, RoundingMode.HALF_UP))
                        .toPlainString();
    }

    private static long number(ReviewSettings s, String key, long fallback) {
        Optional<String> v = s.value(key);
        if (v.isEmpty()) return fallback;
        try {
            return Long.parseLong(v.get().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    key + " takes a whole number, not '" + v.get() + "'");
        }
    }

    private static BigDecimal decimal(ReviewSettings s, String key, BigDecimal fallback) {
        Optional<String> v = s.value(key);
        if (v.isEmpty()) return fallback;
        try {
            return new BigDecimal(v.get().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " takes a number, not '" + v.get() + "'");
        }
    }
}
