package io.github.llm4j.loom.review.cost;

import io.github.llm4j.loom.ast.ScheduleDef;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.engine.RuleInfo;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.FixOption;
import io.github.llm4j.loom.review.model.FixSafety;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.model.TextEdit;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The optional-lens rules that need the estimates and so cannot be written as data over facts: how
 * many questions a run can put to a person (LH03, human oversight), how many effects one run can
 * cause (LB02, blast radius), and what a scheduled workflow can spend in a day (LB03, blast
 * radius).
 *
 * <p>Settings: {@code oversight.max-questions} (default 10), {@code blast.max-effects} (default
 * 25), {@code blast.max-daily-cost} (in the currency of the price table; without it LB03 only asks
 * for a per-day budget).
 */
public final class PostureRules implements FindingSource {

    private final CostEstimator estimator;

    public PostureRules(CostEstimator estimator) {
        this.estimator = estimator;
    }

    /** What is known about these rules, for {@code --explain}. */
    public static List<RuleInfo> catalog() {
        return List.of(
                new RuleInfo(
                        "LH03",
                        Lens.OVERSIGHT,
                        Severity.MEDIUM,
                        "A run can put too many questions to a person",
                        "People answer the first questions with care and the tenth with a click. When the worst case of one run is more than oversight.max-questions (default 10) human_prompt and decide questions, the answers stop meaning anything. What to do: ask fewer, or ask once about the whole batch.",
                        "posture"),
                new RuleInfo(
                        "LB02",
                        Lens.BLAST_RADIUS,
                        Severity.MEDIUM,
                        "One run can cause too many effects",
                        "From the tools its agents hold, tool rounds, retries and repeats, a workflow can cause at most so many sends and writes in one run. When that is more than blast.max-effects (default 25), or has no bound, one bad run can do a lot. What to do: cap the calls with a budget, bound the loops, or split the workflow.",
                        "posture"),
                new RuleInfo(
                        "LB03",
                        Lens.BLAST_RADIUS,
                        Severity.MEDIUM,
                        "A scheduled workflow has no limit on what it spends in a day",
                        "A schedule repeats a workflow without anyone looking. Without a budget per day, a mistake repeats at full price until someone notices. With blast.max-daily-cost and a price table, the worst case of one run times the runs per day is also compared with it. What to do: add budget { tokens: N per day }.",
                        "posture"));
    }

    @Override
    public String name() {
        return "posture rules";
    }

    @Override
    public void collect(ReviewInput input, ReviewSettings settings, Sink sink) {
        boolean oversight = settings.lensOn(Lens.OVERSIGHT);
        boolean blast = settings.lensOn(Lens.BLAST_RADIUS);
        if (!oversight && !blast) return;
        CostEstimate estimate = estimator.estimate(input, settings);
        long maxQuestions = number(settings, "oversight.max-questions", 10);
        long maxEffects = number(settings, "blast.max-effects", 25);
        for (WorkflowCost w : estimate.workflows()) {
            int line =
                    input.script().getWorkflows().stream()
                            .filter(x -> x.getName().equals(w.name()))
                            .mapToInt(WorkflowDef::getLine)
                            .findFirst()
                            .orElse(0);
            if (oversight) questions(w, input, line, maxQuestions, sink);
            if (blast) {
                effects(w, input, line, maxEffects, sink);
                daily(estimate, w, input, settings, sink);
            }
        }
    }

    private static void questions(WorkflowCost w, ReviewInput in, int line, long limit, Sink sink) {
        TimeEstimate t = w.time();
        if (t == null || !(t.questionsUnbounded() || t.worstQuestions() > limit)) return;
        sink.finding(
                finding(
                        "LH03",
                        Lens.OVERSIGHT,
                        Severity.MEDIUM,
                        Location.at(in.file(), line),
                        "Workflow "
                                + w.name()
                                + " can put "
                                + (t.questionsUnbounded()
                                        ? "an unlimited number of (at least "
                                                + t.worstQuestions()
                                                + ")"
                                        : "up to " + t.worstQuestions())
                                + " questions to a person in one run, more than "
                                + limit,
                        "Past the first few, people answer without reading, so the answers stop being a check.",
                        "Ask fewer questions, or one question about the whole batch.",
                        Optional.empty()));
    }

    private static void effects(WorkflowCost w, ReviewInput in, int line, long limit, Sink sink) {
        EffectsEstimate e = w.effects();
        if (e == null || e.none()) return;
        long worst = e.sends().worst() + e.writes().worst();
        boolean unbounded = e.sends().unbounded() || e.writes().unbounded();
        if (!unbounded && worst <= limit) return;
        sink.finding(
                finding(
                        "LB02",
                        Lens.BLAST_RADIUS,
                        Severity.MEDIUM,
                        Location.at(in.file(), line),
                        "One run of "
                                + w.name()
                                + " can cause "
                                + (unbounded
                                        ? "an unlimited number of (at least " + worst + ")"
                                        : "up to " + worst)
                                + " sends and writes, more than "
                                + limit,
                        "The more a single run can do to the world, the more a single mistake costs. The count comes from the tools the agents hold, their rounds, retries and repeats.",
                        "Cap the calls with a budget { calls: N }, bound the loops, or split the workflow.",
                        Optional.empty()));
    }

    private void daily(
            CostEstimate estimate,
            WorkflowCost w,
            ReviewInput in,
            ReviewSettings settings,
            Sink sink) {
        for (ScheduleDef s : in.script().getSchedules()) {
            if (!w.name().equals(s.getRunWorkflow())) continue;
            var budget = in.script().getBudget();
            boolean perDay =
                    budget != null
                            && budget.getWindow() == io.github.llm4j.budget.Window.DAY
                            && budget.hasLimits();
            long runs = runsPerDay(s);
            Location where = Location.at(in.file(), s.getLine());
            if (!perDay) {
                sink.finding(
                        finding(
                                "LB03",
                                Lens.BLAST_RADIUS,
                                Severity.MEDIUM,
                                where,
                                "Schedule "
                                        + s.getName()
                                        + " runs "
                                        + w.name()
                                        + (runs > 0 ? " up to " + runs + " times a day" : "")
                                        + " with no budget per day",
                                "Nobody is watching a schedule run, so a mistake repeats at full price until someone notices.",
                                "Add budget { tokens: N per day }.",
                                budget == null ? Optional.of(budgetOption(in)) : Optional.empty()));
                continue;
            }
            Optional<String> max = settings.value("blast.max-daily-cost");
            if (max.isEmpty() || runs <= 0 || estimate.prices() == null) continue;
            BigDecimal limit = new BigDecimal(max.get().strip());
            Usage.Money m = estimate.worstMoney(w);
            if (w.unbounded() || !m.complete()) continue;
            BigDecimal day = m.amount().multiply(BigDecimal.valueOf(runs));
            if (day.compareTo(limit) > 0)
                sink.finding(
                        finding(
                                "LB03",
                                Lens.BLAST_RADIUS,
                                Severity.MEDIUM,
                                where,
                                "Schedule "
                                        + s.getName()
                                        + " can spend up to "
                                        + day.setScale(2, java.math.RoundingMode.HALF_UP)
                                                .toPlainString()
                                        + " a day ("
                                        + runs
                                        + " runs of up to "
                                        + m.amount()
                                                .setScale(2, java.math.RoundingMode.HALF_UP)
                                                .toPlainString()
                                        + "), more than "
                                        + limit.toPlainString(),
                                "The worst case of one run times the runs a day is over the limit the project set (blast.max-daily-cost).",
                                "Make the workflow cheaper, run it less often, or lower the per-day budget.",
                                Optional.empty()));
        }
    }

    /** A per-day token budget the developer sizes. */
    private static FixOption budgetOption(ReviewInput in) {
        return new FixOption(
                "budget-per-day",
                "Add a budget per day",
                "Once the day's tokens are spent, the run stops or asks, whatever the schedule says. Size it above what a normal day needs.",
                FixSafety.REVIEW,
                List.of(
                        new FixOption.Param(
                                "tokens", "Most tokens the script may use in a day", "200000")),
                List.of(
                        new TextEdit(
                                in.file(),
                                1,
                                1,
                                1,
                                1,
                                "budget { tokens: {ask.tokens} per day }\n")),
                false);
    }

    /** How many times a day a schedule fires, at most; 0 when it cannot be told. */
    static long runsPerDay(ScheduleDef s) {
        if (s.getEvery() != null && s.getEvery().toMillis() > 0)
            return Math.max(1, 86_400_000L / s.getEvery().toMillis());
        String cron = s.getCron();
        if (cron == null) return 0;
        String[] f = cron.trim().split("\\s+");
        if (f.length < 2) return 0;
        long minutes = count(f[0], 0, 59);
        long hours = count(f[1], 0, 23);
        return minutes <= 0 || hours <= 0 ? 0 : minutes * hours;
    }

    /** How many values a cron field names: {@code *}, {@code *}/n, a-b, a-b/n and lists. */
    private static long count(String field, int min, int max) {
        long total = 0;
        for (String part : field.split(",")) {
            String range = part;
            long step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                try {
                    step = Math.max(1, Long.parseLong(part.substring(slash + 1)));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            long lo = min;
            long hi = max;
            try {
                if (!range.equals("*")) {
                    int dash = range.indexOf('-');
                    if (dash >= 0) {
                        lo = Long.parseLong(range.substring(0, dash));
                        hi = Long.parseLong(range.substring(dash + 1));
                    } else {
                        lo = Long.parseLong(range);
                        hi = slash >= 0 ? max : lo;
                    }
                }
            } catch (NumberFormatException e) {
                return 0;
            }
            if (hi < lo) return 0;
            total += (hi - lo) / step + 1;
        }
        return total;
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

    private static Finding finding(
            String rule,
            Lens lens,
            Severity sev,
            Location where,
            String title,
            String why,
            String fix,
            Optional<FixOption> option) {
        return new Finding(
                rule,
                lens,
                sev,
                where,
                title,
                why,
                fix,
                List.of(),
                Optional.empty(),
                List.of(),
                "posture",
                "",
                option.map(List::of).orElse(List.of()));
    }
}
