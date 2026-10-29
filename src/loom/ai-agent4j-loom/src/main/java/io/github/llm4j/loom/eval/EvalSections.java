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
        l.add(
                "Each case ran once: a difference of a few points between two runs may be noise, not a change.");
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

    // ── 2. Overall ───────────────────────────────────────────────────────────────────────────

    private static List<String> overall(EvalReport.Run run) {
        List<String> l = new ArrayList<>();
        l.addAll(EvalReport.summary(run).lines().toList());
        return l;
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
            if (r.dimensions().isEmpty()) continue;
            for (String d : r.dimensions()) {
                long[] c = counts.computeIfAbsent(d, k -> new long[3]);
                cases.computeIfAbsent(d, k -> new java.util.LinkedHashSet<>()).add(r.label());
                for (ScenarioResult.Check ch : r.checks()) c[index(ch.status())]++;
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
        if (run.notRun() > 0) l.add(run.notRun() + " case(s) were not run because a limit stopped the evaluation.");
        if (run.stoppedBecause() != null) l.add("Stopped early: " + run.stoppedBecause());
        boolean heldOut = run.results().stream().anyMatch(r -> r.tags().contains("split:heldout"));
        if (!heldOut)
            l.add("There are no held-out cases: nothing here tells you whether prompts were tuned to the cases you can see (tag a sealed set split:heldout).");
        if (run.judge() != null && !run.judge().validated())
            l.add("The judge has not been validated against human scores, so its verdicts may be systematically off.");
        slices(run).forEach((tag, rs) -> {
            if (rs.size() < SMALL_SLICE)
                l.add("Slice " + tag + " has fewer than " + SMALL_SLICE + " cases (" + rs.size() + "): read it as an anecdote, not a rate.");
        });
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
        l.add("Each case ran once: a small difference between two runs may be noise.");
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
