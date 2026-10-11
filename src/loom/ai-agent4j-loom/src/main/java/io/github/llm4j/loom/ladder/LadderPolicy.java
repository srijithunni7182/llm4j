package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LadderDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.MeasureDef;
import io.github.llm4j.loom.ast.RungDef;
import io.github.llm4j.loom.autonomy.expr.Expr;
import io.github.llm4j.loom.autonomy.expr.Interpreter;
import io.github.llm4j.loom.autonomy.expr.V;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A ladder bound to a decision, ready to be asked questions: who climbs, who slips, which case goes to a person, which is sampled. Pure: it
 * reads a history and the time it is given and decides nothing about the world, so every answer can be tested without a run.
 */
public final class LadderPolicy {

    /** One condition of a rule with what the history says: {@code history.count >= 20}, actual {@code 15}, needed {@code 20}. */
    public record Term(String text, String actual, String needed, Optional<Boolean> met) { }

    private final LadderDef ladder;
    private final DecisionDef decision;
    private final Map<String, Interpreter.Measure> measures;
    private final Map<String, V> params;
    private final List<MeasureDef> measureDefs;

    private LadderPolicy(LadderDef ladder, DecisionDef decision, Map<String, Interpreter.Measure> measures, Map<String, V> params, List<MeasureDef> defs) {
        this.ladder = ladder;
        this.decision = decision;
        this.measures = measures;
        this.params = params;
        this.measureDefs = defs;
    }

    /** The ladder the decision binds, with the script's measures and the values the binding supplies. */
    public static LadderPolicy of(LoomScript script, DecisionDef decision) {
        LadderDef ladder = script.getLadders().stream().filter(l -> l.getName().equals(decision.getLadder())).findFirst()
                .orElseThrow(() -> new IllegalStateException("decision " + decision.getName() + " binds ladder " + decision.getLadder() + ", which is not defined"));
        Map<String, Interpreter.Measure> measures = new LinkedHashMap<>();
        for (MeasureDef m : script.getMeasures()) measures.put(m.getName(), new Interpreter.Measure(m.getName(), m.getParam(), m.getBody()));
        Interpreter.Measure dangerous = CaseFields.dangerous(decision);
        if (dangerous != null) measures.putIfAbsent("dangerous", dangerous);
        Map<String, V> params = new LinkedHashMap<>();
        Interpreter.Context literals = new Interpreter.Context(Map.of(), Map.of(), 0);
        decision.getLadderArgs().forEach((k, e) -> params.put(k, Interpreter.eval(e, literals)));
        return new LadderPolicy(ladder, decision, measures, params, script.getMeasures());
    }

    // ---- the shape -----------------------------------------------------------------------------------------------------

    public String decisionName() { return decision.getName(); }

    public String ladderName() { return ladder.getName(); }

    public List<String> rungs() {
        List<String> names = new ArrayList<>();
        for (RungDef r : ladder.getRungs()) names.add(r.getName());
        return names;
    }

    public int indexOf(String rung) { return ladder.indexOf(rung); }

    public RungDef.Mode modeOf(int rung) { return ladder.getRungs().get(rung).getMode(); }

    public String nameOf(int rung) { return ladder.getRungs().get(rung).getName(); }

    /** The highest rung this decision may reach: the one it names, or the first. */
    public int ceiling() {
        int i = decision.getCeilingRung() == null ? 0 : ladder.indexOf(decision.getCeilingRung());
        return Math.max(0, i);
    }

    public boolean automatic() { return ladder.isAutomatic(); }

    public String approver() { return ladder.getApprover(); }

    /** The first rung that is autonomous, or -1. */
    public int firstAutonomous() {
        for (int i = 0; i < ladder.getRungs().size(); i++) if (ladder.getRungs().get(i).getMode() == RungDef.Mode.AUTONOMOUS) return i;
        return -1;
    }

    public DecisionDef decision() { return decision; }

    // ---- the rules -----------------------------------------------------------------------------------------------------

    private Interpreter.Context context(V.Cs history, V.Rec current, long now) {
        Map<String, V> vars = new LinkedHashMap<>(params);
        vars.put("history", history);
        if (current != null) vars.put("case", current);
        return new Interpreter.Context(vars, measures, now);
    }

    /** True when the condition to move up into rung {@code to} holds. The first rung has none, so it never does; nor does an empty history. */
    public boolean climbs(int to, V.Cs history, long now) {
        if (to <= 0 || to >= ladder.getRungs().size()) return false;
        Expr climb = ladder.getRungs().get(to).getClimb();
        return climb != null && Interpreter.test(climb, context(history, null, now));
    }

    /** The rung to slip to when the condition to move down out of {@code from} holds. */
    public Optional<Integer> slip(int from, V.Cs history, long now) {
        if (from <= 0 || from >= ladder.getRungs().size()) return Optional.empty();
        RungDef r = ladder.getRungs().get(from);
        if (r.getSlip() == null || !Interpreter.test(r.getSlip(), context(history, null, now))) return Optional.empty();
        int to = r.getSlipTo() == null ? from - 1 : ladder.indexOf(r.getSlipTo());
        return Optional.of(to >= 0 && to < from ? to : from - 1);
    }

    /** True when a case at this rung must go to a person whatever the rung's mode says. */
    public boolean escalates(int rung, V.Rec current, V.Cs history, long now) {
        if (rung < 0 || rung >= ladder.getRungs().size()) return false;
        Expr e = ladder.getRungs().get(rung).getEscalate();
        return e != null && Interpreter.test(e, context(history, current, now));
    }

    /** True when this case is one of the share the rung sends to a person blind. Deterministic in the case id: a resume makes the same choice. */
    public boolean sampled(int rung, String caseId) {
        if (rung < 0 || rung >= ladder.getRungs().size()) return false;
        double share = ladder.getRungs().get(rung).getSamplePercent();
        if (share <= 0) return false;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((caseId + "|" + decision.getName() + "|" + nameOf(rung) + "|sample").getBytes(StandardCharsets.UTF_8));
            long n = ((h[0] & 0xffL) << 24 | (h[1] & 0xffL) << 16 | (h[2] & 0xffL) << 8 | (h[3] & 0xffL)) % 10000;
            return n < share * 10000;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- explaining ----------------------------------------------------------------------------------------------------

    /** What is and is not met of the condition to climb into {@code to}, term by term. */
    public List<Term> explainClimb(int to, V.Cs history, long now) {
        if (to <= 0 || to >= ladder.getRungs().size() || ladder.getRungs().get(to).getClimb() == null) return List.of();
        return terms(ladder.getRungs().get(to).getClimb(), "and", context(history, null, now));
    }

    /** What the slip condition of {@code from} says, term by term. */
    public List<Term> explainSlip(int from, V.Cs history, long now) {
        if (from <= 0 || from >= ladder.getRungs().size() || ladder.getRungs().get(from).getSlip() == null) return List.of();
        return terms(ladder.getRungs().get(from).getSlip(), "or", context(history, null, now));
    }

    private static List<Term> terms(Expr e, String connective, Interpreter.Context ctx) {
        List<Expr> leaves = new ArrayList<>();
        flatten(e, connective, leaves);
        List<Term> out = new ArrayList<>();
        for (Expr leaf : leaves) {
            String actual;
            String needed = "";
            if (leaf instanceof Expr.Bin b && isComparison(b.op())) {
                actual = Show.value(Interpreter.eval(b.left(), ctx));
                needed = Show.value(Interpreter.eval(b.right(), ctx));
            } else {
                actual = Show.value(Interpreter.eval(leaf, ctx));
            }
            V whole = Interpreter.eval(leaf, ctx);
            out.add(new Term(Show.unwrapped(leaf.show()), actual, needed, whole instanceof V.Bool b ? Optional.of(b.value()) : Optional.empty()));
        }
        return out;
    }

    private static boolean isComparison(String op) {
        return op.equals("==") || op.equals("!=") || op.equals("<") || op.equals("<=") || op.equals(">") || op.equals(">=");
    }

    private static void flatten(Expr e, String connective, List<Expr> out) {
        if (e instanceof Expr.Bin b && b.op().equals(connective)) {
            flatten(b.left(), connective, out);
            flatten(b.right(), connective, out);
        } else {
            out.add(e);
        }
    }

    // ---- the rules in force --------------------------------------------------------------------------------------------

    private static String collapse(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** The ladder as it stands, in plain text, for the record of which rules were in force when a level changed. */
    public String rulesText() {
        StringBuilder b = new StringBuilder();
        b.append("ladder ").append(ladder.getName()).append(" for decision ").append(decision.getName()).append('\n');
        params.forEach((k, v) -> b.append("  ").append(k).append(" = ").append(Show.value(v)).append('\n'));
        for (RungDef r : ladder.getRungs()) {
            b.append("rung ").append(r.getName()).append('\n').append("  mode: ").append(r.getMode().word()).append('\n');
            if (r.getClimb() != null) b.append("  climb when ").append(collapse(r.getClimbText())).append('\n');
            if (r.getSlip() != null) b.append("  slip").append(r.getSlipTo() == null ? "" : " to " + r.getSlipTo()).append(" when ").append(collapse(r.getSlipText())).append('\n');
            if (r.getSamplePercent() > 0) b.append("  sample ").append(Show.number(r.getSamplePercent() * 100)).append("% blind\n");
            if (r.getEscalate() != null) b.append("  escalate when ").append(collapse(r.getEscalateText())).append('\n');
        }
        b.append(ladder.isAutomatic() ? "climbing is automatic\n" : "climbing needs approval from " + ladder.getApprover() + "\n");
        b.append("ceiling: ").append(nameOf(ceiling())).append('\n');
        for (MeasureDef m : measureDefs) b.append("measure ").append(m.getName()).append('(').append(m.getParam()).append(") = ").append(collapse(m.getText())).append('\n');
        return b.toString();
    }

    /** A hash of the rules in their parsed form: a comment or a change of layout is not a change of rules. */
    public String fingerprint() {
        StringBuilder b = new StringBuilder();
        b.append(ladder.getName()).append('|').append(decision.getName()).append('|').append(nameOf(ceiling())).append('|')
                .append(ladder.isAutomatic() ? "auto" : "approver=" + ladder.getApprover()).append('\n');
        params.forEach((k, v) -> b.append("param ").append(k).append('=').append(Show.value(v)).append('\n'));
        for (RungDef r : ladder.getRungs()) {
            b.append("rung ").append(r.getName()).append(' ').append(r.getMode().word()).append('\n');
            if (r.getClimb() != null) b.append(" climb ").append(r.getClimb().show()).append('\n');
            if (r.getSlip() != null) b.append(" slip ").append(r.getSlipTo()).append(' ').append(r.getSlip().show()).append('\n');
            if (r.getSamplePercent() > 0) b.append(" sample ").append(r.getSamplePercent()).append('\n');
            if (r.getEscalate() != null) b.append(" escalate ").append(r.getEscalate().show()).append('\n');
        }
        for (Interpreter.Measure m : measures.values()) b.append("measure ").append(m.name()).append('(').append(m.param()).append(")=").append(m.body().show()).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
