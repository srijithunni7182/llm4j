package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LadderDef;
import io.github.llm4j.loom.ast.MeasureDef;
import io.github.llm4j.loom.ast.RungDef;
import io.github.llm4j.loom.autonomy.Level;
import io.github.llm4j.loom.autonomy.expr.Checker;
import io.github.llm4j.loom.autonomy.expr.ExprException;
import io.github.llm4j.loom.autonomy.expr.ExprParser;
import io.github.llm4j.loom.autonomy.expr.Interpreter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a decision's {@code trust { }} block as a ladder: three rungs (watch, suggest, act), a
 * climb condition for each step up built from {@code after N cases over D days, agreeing at least
 * P%}, a slip for each {@code drop to …} rule, a sample for {@code check N% of cases} and an
 * escalate for {@code always ask a person when …}. It reads the same evidence the original engine
 * did (the cases a person answered blind, the latest {@code window} of them), so the ladder it
 * writes climbs and slips where the trust block always did.
 *
 * <p>What the language cannot say in one line stays with the original engine, and the result says
 * why: a decision that does not start at watch, that asks a person after N cases <em>a day</em>, or
 * whose {@code always ask} condition is not a comparison of a remembered value.
 */
public final class TrustDesugar {

    /**
     * The name of the ladder written for a decision. It cannot be typed in a script, so it never
     * clashes with one.
     */
    public static final String PREFIX = "trust:";

    static final String BLIND = "trust_blind";
    static final String AGREES = "trust_agrees";
    static final String BY_AGENT = "trust_by_agent";
    static final String MALFORMED = "trust_malformed";
    static final String REVERSED = "trust_reversed";

    private static final Pattern COMPARISON =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+?)\\s*$");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    /** The ladder and measures written for a decision, or the reason none could be. */
    public record Result(
            LadderDef ladder,
            List<MeasureDef> measures,
            Map<String, String> labels,
            String unsupported) {
        public boolean supported() {
            return unsupported == null;
        }
    }

    private TrustDesugar() {}

    public static Result of(DecisionDef d, Set<String> scriptMeasures) {
        try {
            return build(d, scriptMeasures);
        } catch (ExprException e) {
            return no("the rules could not be written as conditions: " + e.getMessage());
        }
    }

    private static Result no(String why) {
        return new Result(null, List.of(), Map.of(), why);
    }

    private static Result build(DecisionDef d, Set<String> scriptMeasures) {
        if (d.getStartAt() != Level.WATCH)
            return no(
                    "it starts at "
                            + d.getStartAt().word()
                            + ", and a ladder always starts on its first rung");
        if (d.getAskAfterPerDay() > 0)
            return no("it asks a person after N cases a day, which counts calendar days");
        for (String taken : List.of(BLIND, AGREES, BY_AGENT, MALFORMED, REVERSED)) {
            if (scriptMeasures.contains(taken))
                return no("the script already has a measure called " + taken);
        }
        int line = d.getLine();
        Map<String, String> labels = new LinkedHashMap<>();
        int window = Math.max(1, d.getWindow());
        boolean dangerous = !d.getDangerous().isEmpty();

        LadderDef ladder = new LadderDef(PREFIX + d.getName(), line);
        ladder.setSynthetic(true);
        RungDef watch = rung("watch", RungDef.Mode.BLIND, line);
        RungDef suggest = rung("suggest", RungDef.Mode.CONFIRM, line);
        RungDef act = rung("act", RungDef.Mode.AUTONOMOUS, line);
        ladder.getRungs().add(watch);
        ladder.getRungs().add(suggest);
        ladder.getRungs().add(act);
        if (d.isAutomatic()) ladder.setAutomatic();
        else ladder.setApprover(d.getApprover());

        for (RungDef r : List.of(suggest, act)) {
            Level level = Level.of(r.getName());
            String text = climb(d.getUpRules().get(level), window, dangerous, labels);
            r.setClimb(
                    ExprParser.parse(text),
                    text,
                    d.getUpRules().get(level) == null ? line : d.getUpRules().get(level).line());
        }

        for (RungDef r : List.of(suggest, act)) {
            int index = Level.of(r.getName()).ordinal();
            Map<Level, List<String>> byTarget = new TreeMap<>();
            Map<Level, Integer> lines = new LinkedHashMap<>();
            for (DecisionDef.DropRule rule : d.getDropRules()) {
                if (rule.to().ordinal() >= index) continue;
                String cond = drop(rule, window, dangerous, labels);
                if (cond == null) continue;
                byTarget.computeIfAbsent(rule.to(), k -> new ArrayList<>()).add(cond);
                lines.putIfAbsent(rule.to(), rule.line());
            }
            byTarget.forEach(
                    (to, conds) -> {
                        String text = String.join(" or ", conds);
                        r.setSlip(ExprParser.parse(text), text, to.word(), lines.get(to));
                    });
        }

        if (d.getAuditPercent() > 0) {
            suggest.setSample(d.getAuditPercent() / 100.0, line);
            act.setSample(d.getAuditPercent() / 100.0, line);
        }

        if (!d.getAskWhen().isEmpty()) {
            Set<String> fields = new LinkedHashSet<>(d.getRemember());
            if (d.getGroupBy() != null) fields.add(d.getGroupBy());
            List<String> conds = new ArrayList<>();
            for (String raw : d.getAskWhen()) {
                String cond = ask(raw, fields);
                if (cond == null)
                    return no(
                            "it always asks a person when \""
                                    + raw
                                    + "\", which is not a comparison of a remembered value with a number or a word");
                conds.add(cond);
            }
            String text = String.join(" or ", conds);
            act.setEscalate(ExprParser.parse(text), text, line);
        }

        List<MeasureDef> measures = new ArrayList<>();
        measures.add(measure(BLIND, "c.blind", line));
        measures.add(measure(AGREES, "c.response.choice == c.proposal.choice", line));
        measures.add(measure(BY_AGENT, "c.response.by == \"agent\"", line));
        measures.add(measure(MALFORMED, "c.proposal.malformed == true", line));
        measures.add(measure(REVERSED, "c.outcomes.reversed == true", line));
        verify(d, ladder, measures);
        return new Result(ladder, measures, labels, null);
    }

    private static RungDef rung(String name, RungDef.Mode mode, int line) {
        RungDef r = new RungDef(name, line);
        r.setMode(mode, line);
        return r;
    }

    private static MeasureDef measure(String name, String body, int line) {
        return new MeasureDef(name, "c", ExprParser.parse(body), body, line);
    }

    // ---- the conditions
    // -------------------------------------------------------------------------------------------------------

    private static String evidence() {
        return "history.where(" + BLIND + ")";
    }

    private static String latest(int window) {
        return evidence() + ".last(" + window + ")";
    }

    /** The condition as written, remembered under the words the trust block would use for it. */
    private static String said(Map<String, String> labels, String condition, String words) {
        labels.put(Show.unwrapped(ExprParser.parse(condition).show()), words);
        return condition;
    }

    private static String climb(
            DecisionDef.UpRule rule, int window, boolean dangerous, Map<String, String> labels) {
        if (rule == null)
            return "false"; // the script has no rule for this step: the agent never earns it
        List<String> parts = new ArrayList<>();
        parts.add(
                said(
                        labels,
                        evidence() + ".count >= " + rule.cases(),
                        "cases answered without seeing the proposal"));
        if (window < rule.cases())
            parts.add(
                    said(
                            labels,
                            latest(window) + ".count >= " + rule.cases(),
                            "cases in the window the agent is judged on"));
        if (rule.days() > 0)
            parts.add(
                    said(
                            labels,
                            evidence() + ".age >= " + rule.days() + "d",
                            "age of the first such case"));
        parts.add(
                said(
                        labels,
                        latest(window)
                                + ".rate("
                                + AGREES
                                + ").lower95 >= "
                                + pct(rule.agreeingAtLeast()),
                        "agreement with the person (lower bound of the 95% interval)"));
        if (dangerous) {
            if (rule.noDangerous())
                parts.add(
                        said(
                                labels,
                                latest(window) + ".count(dangerous) == 0",
                                "dangerous mistakes in the window"));
            if (rule.dangerousAtMost() != null)
                parts.add(
                        said(
                                labels,
                                latest(window)
                                        + ".rate(dangerous) <= "
                                        + pct(rule.dangerousAtMost()),
                                "share of dangerous mistakes in the window"));
        }
        return String.join(" and ", parts);
    }

    private static String drop(
            DecisionDef.DropRule rule, int window, boolean dangerous, Map<String, String> labels) {
        switch (rule.count()) {
            case DANGEROUS_MISTAKES:
                return dangerous
                        ? said(
                                labels,
                                evidence()
                                        + ".last("
                                        + rule.inCases()
                                        + ").count(dangerous) >= "
                                        + rule.n(),
                                "dangerous mistakes in the last " + rule.inCases() + " cases")
                        : null;
            case REVERSALS:
                return said(
                        labels,
                        "history.where("
                                + BY_AGENT
                                + ").last("
                                + rule.inCases()
                                + ").count("
                                + REVERSED
                                + ") >= "
                                + rule.n(),
                        "reversals of the agent's decisions in the last "
                                + rule.inCases()
                                + " of them");
            case UNUSABLE_PROPOSALS:
                return said(
                        labels,
                        "history.last("
                                + rule.inCases()
                                + ").count("
                                + MALFORMED
                                + ") >= "
                                + rule.n(),
                        "unusable proposals in the last " + rule.inCases() + " cases");
            default:
                return said(
                        labels,
                        "("
                                + latest(window)
                                + ".count >= "
                                + Ladder_MIN_FOR_FLOOR
                                + " and "
                                + latest(window)
                                + ".rate("
                                + AGREES
                                + ") < "
                                + pct(rule.percent())
                                + ")",
                        "agreement has fallen below "
                                + pct(rule.percent())
                                + " (judged once there are "
                                + Ladder_MIN_FOR_FLOOR
                                + " cases)");
        }
    }

    /**
     * A floor on agreement is only judged once there are enough cases for the rate to mean
     * something (the original engine's figure).
     */
    private static final int Ladder_MIN_FOR_FLOOR =
            io.github.llm4j.loom.autonomy.Ladder.MIN_FOR_FLOOR;

    /**
     * {@code amount > 200} as {@code case.amount > 200}; null when it is not a comparison of a
     * remembered value.
     */
    static String ask(String raw, Set<String> fields) {
        Matcher m = COMPARISON.matcher(raw);
        if (!m.matches() || !fields.contains(m.group(1))) return null;
        String value = m.group(3).replace("\"", "").trim();
        if (value.isEmpty()) return null;
        String literal =
                NUMBER.matcher(value).matches() ? value : "\"" + value.replace("\\", "\\\\") + "\"";
        return "case." + m.group(1) + " " + m.group(2) + " " + literal;
    }

    private static String pct(double percent) {
        return BigDecimal.valueOf(percent).stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * Every condition written must read: a name it uses exists, a method it calls exists. A mistake
     * here is mine, not the script's, and is found now.
     */
    private static void verify(DecisionDef d, LadderDef ladder, List<MeasureDef> measures) {
        Map<String, Interpreter.Measure> ms = new LinkedHashMap<>();
        for (MeasureDef m : measures)
            ms.put(m.getName(), new Interpreter.Measure(m.getName(), m.getParam(), m.getBody()));
        Interpreter.Measure dangerous = CaseFields.dangerous(d);
        if (dangerous != null) ms.put("dangerous", dangerous);
        Checker climbing = new Checker(CaseFields.forDecision(d), Map.of(), ms, true, false);
        Checker escalating = new Checker(CaseFields.forDecision(d), Map.of(), ms, true, true);
        for (RungDef r : ladder.getRungs()) {
            if (r.getClimb() != null) climbing.condition(r.getClimb());
            for (RungDef.Slip s : r.getSlips()) climbing.condition(s.expr());
            if (r.getEscalate() != null) escalating.condition(r.getEscalate());
        }
    }

    /**
     * Gives every decision with neither a ladder nor a reason to be left alone the ladder written
     * from its trust block.
     */
    public static void apply(io.github.llm4j.loom.ast.LoomScript script) {
        Set<String> taken = new LinkedHashSet<>();
        script.getMeasures().forEach(m -> taken.add(m.getName()));
        for (DecisionDef d : script.getDecisions()) {
            if (d.getLadder() != null) continue;
            Result r = of(d, taken);
            if (!r.supported()) continue;
            script.addLadder(r.ladder());
            d.setLadder(r.ladder().getName(), d.getLine());
            d.setLadderFromTrust(true);
            d.setCeilingRung(d.getCeiling().word(), d.getLine());
            d.getTrustMeasures().addAll(r.measures());
            d.getTrustLabels().putAll(r.labels());
        }
    }
}
