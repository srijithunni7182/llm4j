package io.github.llm4j.loom.eval;

import io.github.llm4j.loom.autonomy.expr.Wilson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The structure of an evaluation report, which does not change: the same nine sections in the same
 * order, in the terminal, in the page and in the JSON, all built from this one place. A section with
 * nothing to show is still there and says why, so a reader never has to wonder whether a part is
 * missing or only empty.
 */
public final class EvalSections {

    /** One section: a stable id for the page and the JSON, a title, and its lines. */
    public record Section(String id, String title, List<String> lines) {}

    /** A slice with fewer cases than this is read as a story, not a rate. */
    static final int SMALL_SLICE = 10;

    /** How many of an agent's worst outputs the By-agent section shows. */
    private static final int WORST = 3;

    private static final int OUTPUT_CHARS = 140;

    private EvalSections() {}

    /** The nine sections of a run, in order. */
    public static List<Section> of(EvalReport.Run run) {
        List<Section> out = new ArrayList<>();
        out.add(new Section("read-first", "Read this first", readFirst(run)));
        out.add(new Section("overall", "Overall", overall(run)));
        out.add(new Section("by-agent", "By agent", byAgent(run)));
        out.add(new Section("by-dimension", "By dimension", byDimension(run)));
        out.add(new Section("by-slice", "By slice", bySlice(run)));
        out.add(new Section("cases", "Cases", cases(run)));
        out.add(new Section("cost", "Cost", cost(run)));
        out.add(new Section("cautions", "Cautions", cautions(run)));
        out.add(new Section("reproduce", "How to reproduce", reproduce(run)));
        return out;
    }

    /** The report as text for a terminal: a heading per section, then its lines. */
    public static String terminal(EvalReport.Run run) {
        StringBuilder b = new StringBuilder();
        for (Section s : of(run)) {
            b.append("== ").append(s.title()).append(" ==\n");
            s.lines().forEach(l -> b.append(l).append('\n'));
            b.append('\n');
        }
        return b.toString();
    }

    // ── 1. Read this first ───────────────────────────────────────────────────────────────────

    private static List<String> readFirst(EvalReport.Run run) {
        List<String> l = new ArrayList<>();
        long agents = run.results().stream().filter(r -> r.kind().equals("agent")).count();
        long workflows = run.results().size() - agents;
        l.add(
                (run.mock() ? "Mock run" : "Real run")
                        + " of "
                        + run.script()
                        + ": "
                        + run.results().size()
                        + " case"
                        + (run.results().size() == 1 ? "" : "s")
                        + " ("
                        + agents
                        + " agent, "
                        + workflows
                        + " workflow) over "
                        + agentNames(run).size()
                        + " agent"
                        + (agentNames(run).size() == 1 ? "" : "s")
                        + ".");
        l.add(
                run.count(Status.PASS)
                        + " passed, "
                        + run.count(Status.FAIL)
                        + " failed, "
                        + run.count(Status.UNJUDGED)
                        + " unjudged (unjudged is not a pass).");
        l.addAll(consistency(run));
        l.add("Release gate: " + run.settings().getOrDefault("release gate", "no") + ".");
        Double gap = generalisationGap(run);
        if (gap != null)
            l.add(
                    String.format(
                            Locale.ROOT,
                            "Generalisation gap (validation minus held-out pass rate): %+.0f points.",
                            gap * 100));
        EvalReport.JudgeFacts j = run.judge();
        l.add(
                j == null
                        ? "Judge: none ran" + (run.mock() ? " (a mock run does not judge)." : ".")
                        : "Judge: "
                                + j.model()
                                + ", "
                                + j.calls()
                                + " call"
                                + (j.calls() == 1 ? "" : "s")
                                + (j.validated()
                                        ? ", validated against human scores."
                                        : ", not validated against human scores."));
        BigDecimal total = totalCost(run);
        l.add("Cost: " + (total == null ? "unknown (no prices given: add --prices)" : money(total)) + ".");
        run.settings().forEach((k, v) -> l.add("Fingerprint: " + k + " = " + v));
        return l;
    }

    /** How many times each case ran, as the run was told. */
    private static int repetitions(EvalReport.Run run) {
        try {
            return Math.max(1, Integer.parseInt(run.settings().getOrDefault("repetitions", "1")));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** The runs of each case, in the order they ran, by target and label. */
    private static Map<String, List<ScenarioResult>> byCase(EvalReport.Run run) {
        Map<String, List<ScenarioResult>> out = new LinkedHashMap<>();
        for (ScenarioResult r : run.results())
            out.computeIfAbsent(r.target() + " · " + r.label(), k -> new ArrayList<>()).add(r);
        return out;
    }

    /**
     * What repetition shows and what this run can resolve: pass@1 beside pass^N and consistency
     * when cases ran more than once, and always how finely the case count lets a pass rate be read.
     */
    private static List<String> consistency(EvalReport.Run run) {
        List<String> l = new ArrayList<>();
        Map<String, List<ScenarioResult>> cases = byCase(run);
        int reps = repetitions(run);
        if (reps == 1)
            l.add("Each case ran once: a difference of a few points between two runs may be noise, not a change.");
        else if (!cases.isEmpty()) {
            long firstPass = cases.values().stream().filter(rs -> rs.get(0).status() == Status.PASS).count();
            long allPass =
                    cases.values().stream()
                            .filter(rs -> rs.stream().allMatch(r -> r.status() == Status.PASS))
                            .count();
            long same =
                    cases.values().stream()
                            .filter(rs -> rs.stream().map(ScenarioResult::status).distinct().count() == 1)
                            .count();
            l.add(
                    "Each case ran "
                            + reps
                            + " times: pass@1 "
                            + percent(firstPass, cases.size())
                            + " (the first run passed), pass^"
                            + reps
                            + " "
                            + percent(allPass, cases.size())
                            + " (every run passed), consistency "
                            + percent(same, cases.size())
                            + " (every run gave the same result).");
        }
        if (!cases.isEmpty()) {
            int n = cases.size();
            double halfWidth = (Wilson.upper(n / 2, n) - Wilson.lower(n / 2, n)) / 2;
            l.add(
                    "Resolution: with "
                            + n
                            + " case"
                            + (n == 1 ? "" : "s")
                            + " a pass rate is known to within about ±"
                            + Math.round(halfWidth * 100)
                            + " points (95% interval at 50%); a smaller change between two runs is below this run's resolution.");
        }
        return l;
    }

    /** Validation pass rate minus held-out pass rate, when both were run; null otherwise. */
    static Double generalisationGap(EvalReport.Run run) {
        Double validation = passRate(run, "split:validation");
        Double heldOut = passRate(run, "split:heldout");
        return validation == null || heldOut == null ? null : validation - heldOut;
    }

    private static Double passRate(EvalReport.Run run, String tag) {
        long pass = 0, fail = 0;
        for (ScenarioResult r : run.results())
            if (r.tags().contains(tag)) {
                if (r.status() == Status.PASS) pass++;
                else if (r.status() == Status.FAIL) fail++;
            }
        return pass + fail == 0 ? null : (double) pass / (pass + fail);
    }

    // ── 2. Overall ───────────────────────────────────────────────────────────────────────────

    private static List<String> overall(EvalReport.Run run) {
        List<String> l = new ArrayList<>();
        l.addAll(EvalReport.summary(run).lines().toList());
        int[] v = verdictCounts(run);
        if (v[0] + v[1] + v[2] > 0)
            l.add("answer verdicts: " + v[0] + " in the accepted set, " + v[1] + " rejected, " + v[2] + " unmatched.");
        l.addAll(run.baseline());
        return l;
    }

    /** How many verdict checks were in the accepted set, rejected or matched nothing. */
    private static int[] verdictCounts(EvalReport.Run run) {
        int[] counts = new int[3];
        for (ScenarioResult r : run.results())
            for (ScenarioResult.Check c : r.checks()) {
                if (!c.kind().equals("answer verdict") || c.detail() == null) continue;
                if (c.detail().startsWith("verdict: exact") || c.detail().startsWith("verdict: acceptable")) counts[0]++;
                else if (c.detail().startsWith("verdict: rejected")) counts[1]++;
                else if (c.detail().startsWith("verdict: unmatched")) counts[2]++;
            }
        return counts;
    }

    // ── 3. By agent ──────────────────────────────────────────────────────────────────────────

    /** The agents of a run: every agent a case targeted and every agent the traces show. */
    static List<String> agentNames(EvalReport.Run run) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (ScenarioResult r : run.results()) {
            if (r.kind().equals("agent")) names.add(r.target());
            r.agents().forEach(a -> names.add(a.agent()));
        }
        return new ArrayList<>(names);
    }

    private static List<String> byAgent(EvalReport.Run run) {
        List<String> names = agentNames(run);
        if (names.isEmpty())
            return List.of(
                    "no agent ran: "
                            + (run.mock()
                                    ? "a mock run asks no model, so there is nothing per agent."
                                    : "the dataset had no case that reached an agent."));
        List<String> l = new ArrayList<>();
        l.add("(quality is measured from the cases aimed at one agent; cost, cut answers and fallbacks from every run it took part in)");
        for (String name : names) {
            List<ScenarioResult> aimed =
                    run.results().stream()
                            .filter(r -> r.kind().equals("agent") && r.target().equals(name))
                            .toList();
            long pass = aimed.stream().filter(r -> r.status() == Status.PASS).count();
            long fail = aimed.stream().filter(r -> r.status() == Status.FAIL).count();
            long unjudged = aimed.stream().filter(r -> r.status() == Status.UNJUDGED).count();
            long decisive = pass + fail;
            long calls = 0, tokens = 0, cut = 0, fell = 0;
            BigDecimal cost = null;
            for (ScenarioResult r : run.results())
                for (AgentFacts a : r.agents())
                    if (a.agent().equals(name)) {
                        calls += a.calls();
                        tokens += a.tokens();
                        cut += a.cutAnswers();
                        fell += a.fallbacks();
                        if (a.cost() != null) cost = cost == null ? a.cost() : cost.add(a.cost());
                    }
            StringBuilder row = new StringBuilder("  ").append(name).append("   ");
            if (aimed.isEmpty())
                row.append("quality not measured on its own: it ran only inside workflow runs");
            else if (decisive == 0)
                row.append("0 of 0 judged (").append(unjudged).append(" unjudged)");
            else
                row.append(pass)
                        .append(" of ")
                        .append(decisive)
                        .append(" passed (")
                        .append(percent(pass, decisive))
                        .append(", 95% interval ")
                        .append(percent(Wilson.lower((int) pass, (int) decisive)))
                        .append(" to ")
                        .append(percent(Wilson.upper((int) pass, (int) decisive)))
                        .append(unjudged > 0 ? ", " + unjudged + " unjudged" : "")
                        .append(")");
            row.append(";  ")
                    .append(calls)
                    .append(" call")
                    .append(calls == 1 ? "" : "s")
                    .append(", ")
                    .append(tokens)
                    .append(" tokens, ")
                    .append(cost == null ? "cost unknown" : money(cost))
                    .append(", ")
                    .append(cut)
                    .append(" cut, ")
                    .append(fell)
                    .append(" fallback")
                    .append(fell == 1 ? "" : "s");
            l.add(row.toString());
            aimed.stream()
                    .filter(r -> r.status() == Status.FAIL)
                    .limit(WORST)
                    .forEach(
                            r ->
                                    l.add(
                                            "      worst: "
                                                    + r.label()
                                                    + ": \""
                                                    + clip(r.output())
                                                    + "\" ("
                                                    + why(r)
                                                    + ")"));
        }
        return l;
    }

    private static String why(ScenarioResult r) {
        if (r.error() != null) return r.error();
        return r.checks().stream()
                .filter(c -> c.status() == Status.FAIL)
                .map(c -> c.kind() + (c.text().isEmpty() ? "" : " " + c.text()) + ": " + c.detail())
                .findFirst()
                .orElse("no reason recorded");
    }

    // ── 4. By dimension ──────────────────────────────────────────────────────────────────────

    private static List<String> byDimension(EvalReport.Run run) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> cases = new LinkedHashMap<>();
        for (ScenarioResult r : run.results()) {
            for (ScenarioResult.Check ch : r.checks()) {
                // a line that names its dimension counts there only; a plain one under every
                // dimension of its case
                List<String> where = ch.dimension() != null ? List.of(ch.dimension()) : r.dimensions();
                for (String d : where) {
                    long[] c = counts.computeIfAbsent(d, k -> new long[3]);
                    cases.computeIfAbsent(d, k -> new java.util.LinkedHashSet<>()).add(r.label());
                    c[index(ch.status())]++;
                }
            }
        }
        if (counts.isEmpty())
            return List.of(
                    "no dimension: no case names one (add dimensions: [accuracy, ...] to a case to see results by quality dimension).");
        List<String> l = new ArrayList<>();
        counts.forEach(
                (d, c) ->
                        l.add(
                                "  "
                                        + d
                                        + "   "
                                        + c[0]
                                        + " passed, "
                                        + c[1]
                                        + " failed, "
                                        + c[2]
                                        + " unjudged ("
                                        + cases.get(d).size()
                                        + " case"
                                        + (cases.get(d).size() == 1 ? "" : "s")
                                        + ")"));
        if (!run.dimensions().isEmpty()) {
            l.add("  meaning:");
            run.dimensions().forEach((k, v) -> l.add("    " + k + (v.isEmpty() ? "" : ": " + v)));
        }
        return l;
    }

    private static int index(Status s) {
        return switch (s) {
            case PASS -> 0;
            case FAIL -> 1;
            case UNJUDGED -> 2;
        };
    }

    // ── 5. By slice ──────────────────────────────────────────────────────────────────────────

    /** The slices a run has: tags of the form {@code key:value}, other than the agent. */
    private static Map<String, List<ScenarioResult>> slices(EvalReport.Run run) {
        Map<String, List<ScenarioResult>> out = new TreeMap<>();
        for (ScenarioResult r : run.results())
            for (String tag : r.tags()) {
                int colon = tag.indexOf(':');
                if (colon <= 0 || tag.startsWith("agent:")) continue;
                out.computeIfAbsent(tag, k -> new ArrayList<>()).add(r);
            }
        return out;
    }

    private static List<String> bySlice(EvalReport.Run run) {
        Map<String, List<ScenarioResult>> slices = slices(run);
        if (slices.isEmpty())
            return List.of(
                    "no slices: no case carries a key:value tag such as split:validation, difficulty:hard or class:unanswerable.");
        List<String> l = new ArrayList<>();
        slices.forEach(
                (tag, rs) -> {
                    long pass = rs.stream().filter(r -> r.status() == Status.PASS).count();
                    long fail = rs.stream().filter(r -> r.status() == Status.FAIL).count();
                    long unjudged = rs.size() - pass - fail;
                    l.add(
                            "  "
                                    + tag
                                    + "   "
                                    + rs.size()
                                    + " case"
                                    + (rs.size() == 1 ? "" : "s")
                                    + ": "
                                    + pass
                                    + " passed, "
                                    + fail
                                    + " failed, "
                                    + unjudged
                                    + " unjudged"
                                    + (rs.size() < SMALL_SLICE
                                            ? "  [fewer than " + SMALL_SLICE + " cases: an anecdote, not a rate]"
                                            : ""));
                });
        return l;
    }

    // ── 6. Cases ─────────────────────────────────────────────────────────────────────────────

    private static List<String> cases(EvalReport.Run run) {
        if (run.results().isEmpty()) return List.of("no case ran.");
        List<String> l = new ArrayList<>();
        for (ScenarioResult r : run.results()) {
            l.add(EvalReport.mark(r.status()) + " " + r.target() + " · " + r.label());
            if (r.input() != null) l.add("    input:  " + clip(r.input()));
            l.add(
                    "    output: "
                            + (r.output() == null ? "(none: the run did not get that far)" : clip(r.output())));
            if (r.error() != null) l.add("    error: " + r.error());
            for (ScenarioResult.Check c : r.checks()) {
                l.add(
                        "    "
                                + EvalReport.mark(c.status())
                                + " "
                                + c.kind()
                                + (c.text().isEmpty() ? "" : ": " + c.text()));
                if (c.detail() != null) l.add("        " + c.detail());
            }
            if (r.cost() != null) l.add("    cost: " + money(r.cost()));
        }
        return l;
    }

    // ── 7. Cost ──────────────────────────────────────────────────────────────────────────────

    /** What the agents cost across the run, from the traces; null when no price was known. */
    private static BigDecimal agentCost(EvalReport.Run run) {
        BigDecimal sum = null;
        for (ScenarioResult r : run.results())
            for (AgentFacts a : r.agents())
                if (a.cost() != null) sum = sum == null ? a.cost() : sum.add(a.cost());
        return sum;
    }

    private static BigDecimal totalCost(EvalReport.Run run) {
        BigDecimal agents = agentCost(run);
        BigDecimal judge = run.judge() == null ? null : run.judge().cost();
        if (agents == null && judge == null) return null;
        return (agents == null ? BigDecimal.ZERO : agents).add(judge == null ? BigDecimal.ZERO : judge);
    }

    private static List<String> cost(EvalReport.Run run) {
        List<String> l = new ArrayList<>();
        long tokens = 0;
        for (ScenarioResult r : run.results()) for (AgentFacts a : r.agents()) tokens += a.tokens();
        BigDecimal agents = agentCost(run);
        l.add(
                "agents: "
                        + (agents == null ? "cost unknown (no prices given: add --prices)" : money(agents))
                        + ", "
                        + tokens
                        + " tokens");
        EvalReport.JudgeFacts j = run.judge();
        l.add(
                j == null
                        ? "judge: none ran"
                        : "judge: "
                                + (j.cost() == null ? "cost unknown" : money(j.cost()))
                                + ", "
                                + j.calls()
                                + " call"
                                + (j.calls() == 1 ? "" : "s")
                                + ", "
                                + (j.promptTokens() + j.completionTokens())
                                + " tokens ("
                                + j.model()
                                + ")");
        BigDecimal total = totalCost(run);
        l.add("total: " + (total == null ? "unknown" : money(total)) + "  (agents and judge together)");
        long passing = run.count(Status.PASS);
        l.add(
                total == null
                        ? "per passing case: unknown without prices"
                        : passing == 0
                                ? "per passing case: none passed, so there is no cost per success"
                                : "per passing case: "
                                        + money(total.divide(BigDecimal.valueOf(passing), 6, RoundingMode.HALF_UP)));
        l.addAll(CostTail.of(run.results()).lines(EvalSections::money));
        return l;
    }

    // ── 8. Cautions ──────────────────────────────────────────────────────────────────────────

    private static List<String> cautions(EvalReport.Run run) {
        List<String> l = new ArrayList<>();
        if (run.mock())
            l.add("This was a mock run: nothing was judged and nothing was spent.");
        long unjudged = run.count(Status.UNJUDGED);
        if (unjudged > 0)
            l.add(unjudged + " case" + (unjudged == 1 ? " is" : "s are") + " unjudged: nothing confirmed " + (unjudged == 1 ? "it" : "them") + ". Unjudged is not a pass.");
        int[] verdicts = verdictCounts(run);
        int judged = verdicts[0] + verdicts[1] + verdicts[2];
        if (judged > 0 && verdicts[2] * 10 > judged)
            l.add(
                    Math.round(100.0 * verdicts[2] / judged)
                            + "% of the verdicts matched nothing the cases list: above about 10% the ground truth is incomplete and every other number is suspect. Have a person read the unmatched answers and add the right ones to accepted_equivalents.");
        if (run.notRun() > 0) l.add(run.notRun() + " case(s) were not run because a limit stopped the evaluation.");
        if (run.stoppedBecause() != null) l.add("Stopped early: " + run.stoppedBecause());
        boolean heldOut = run.results().stream().anyMatch(r -> Splits.isHeldOut(r.tags()));
        int leftOut = 0;
        try {
            leftOut = Integer.parseInt(run.settings().getOrDefault("held-out not run", "0"));
        } catch (NumberFormatException e) {
            leftOut = 0;
        }
        if (leftOut > 0)
            l.add(
                    leftOut
                            + " held-out case"
                            + (leftOut == 1 ? " was" : "s were")
                            + " not run: they run only at a release gate (--release-gate), so that no one looks at them while tuning.");
        else if (!heldOut)
            l.add("There are no held-out cases: nothing here tells you whether prompts were tuned to the cases you can see (tag a sealed set split:heldout).");
        Double gap = generalisationGap(run);
        if (gap != null && gap > 0.10)
            l.add(
                    String.format(
                            Locale.ROOT,
                            "The held-out pass rate is %.0f points below validation: the prompts may be fitted to the cases you can see; refresh the dataset.",
                            gap * 100));
        if (run.judge() != null && !run.judge().validated())
            l.add("The judge has not been validated against human scores, so its verdicts may be systematically off.");
        slices(run).forEach((tag, rs) -> {
            if (rs.size() < SMALL_SLICE)
                l.add("Slice " + tag + " has fewer than " + SMALL_SLICE + " cases (" + rs.size() + "): read it as an anecdote, not a rate.");
        });
        long shared = 0;
        for (ScenarioResult r : run.results())
            if (r.dimensions().size() > 1)
                shared +=
                        r.checks().stream()
                                .filter(c -> c.kind().equals("rubric") && c.dimension() == null)
                                .count();
        if (shared > 0)
            l.add(
                    shared
                            + " rubric line(s) are counted under every dimension of their case, so one verdict is counted more than once; give each a dimension: ({text: ..., dimension: ...}) to count it once.");
        Map<String, Long> cut = new TreeMap<>();
        Map<String, Long> fell = new TreeMap<>();
        for (ScenarioResult r : run.results())
            for (AgentFacts a : r.agents()) {
                if (a.cutAnswers() > 0) cut.merge(a.agent(), a.cutAnswers(), Long::sum);
                if (a.fallbacks() > 0) fell.merge(a.agent(), a.fallbacks(), Long::sum);
            }
        if (!cut.isEmpty())
            l.add(cut.values().stream().mapToLong(Long::longValue).sum() + " answer(s) were cut at the output limit: " + named(cut) + ". A cut answer is judged as written.");
        if (!fell.isEmpty())
            l.add("Agents that took their fallback: " + named(fell) + ". Text from a fallback did not come from a model.");
        if (repetitions(run) > 1) {
            byCase(run)
                    .forEach(
                            (name, rs) -> {
                                long passed = rs.stream().filter(r -> r.status() == Status.PASS).count();
                                double rate = (double) passed / rs.size();
                                if (rate >= 0.4 && rate <= 0.6)
                                    l.add(
                                            "Unstable: "
                                                    + name.substring(name.indexOf(" · ") + 3)
                                                    + " ("
                                                    + passed
                                                    + " of "
                                                    + rs.size()
                                                    + " passed). A case that passes about half the time is usually under-specified: send it to the dataset's owner.");
                            });
        } else {
            l.add("Each case ran once: a small difference between two runs may be noise.");
        }
        return l;
    }

    private static String named(Map<String, Long> counts) {
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> parts.add(k + " (" + v + ")"));
        return String.join(", ", parts);
    }

    // ── 9. How to reproduce ──────────────────────────────────────────────────────────────────

    private static List<String> reproduce(EvalReport.Run run) {
        List<String> l = new ArrayList<>();
        l.add("Command: " + (run.reproduce() == null ? "weave eval " + run.script() : run.reproduce()));
        run.settings().forEach((k, v) -> l.add("  " + k + " = " + v));
        return l;
    }

    // ── formatting ───────────────────────────────────────────────────────────────────────────

    static String money(BigDecimal amount) {
        return "$" + amount.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(long k, long n) {
        return percent(n == 0 ? 0 : (double) k / n);
    }

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", fraction * 100);
    }

    private static String clip(String text) {
        if (text == null) return "";
        String one = text.replaceAll("\\s+", " ").strip();
        return one.length() <= OUTPUT_CHARS ? one : one.substring(0, OUTPUT_CHARS - 1) + "…";
    }
}
