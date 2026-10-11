package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LadderDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.MeasureDef;
import io.github.llm4j.loom.ast.RungDef;
import io.github.llm4j.loom.autonomy.expr.Checker;
import io.github.llm4j.loom.autonomy.expr.Checker.T;
import io.github.llm4j.loom.autonomy.expr.Expr;
import io.github.llm4j.loom.autonomy.expr.ExprException;
import io.github.llm4j.loom.autonomy.expr.Interpreter;
import io.github.llm4j.loom.autonomy.expr.Suggest;
import io.github.llm4j.loom.execution.ScriptValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Load-time checks for ladders, measures and the decisions that bind them. Every problem names a line and says what to write instead; a
 * misspelt name, a number compared with a duration or a rung that cannot be reached fails here and not after the agent has run.
 */
public final class LadderChecks {

    private static final Set<String> BUILT_IN_FUNCTIONS = Set.of("distance", "abs");

    private final LoomScript script;
    private final ScriptValidator.Checker c;
    private final Map<String, LadderDef> ladders = new LinkedHashMap<>();
    private final Map<String, Interpreter.Measure> measures = new LinkedHashMap<>();
    private final Set<String> reported = new HashSet<>();

    private LadderChecks(ScriptValidator.Checker checker) {
        this.script = checker.script();
        this.c = checker;
    }

    public static void run(ScriptValidator.Checker checker) {
        new LadderChecks(checker).all();
    }

    private void all() {
        declarations();
        for (LadderDef l : ladders.values()) ladder(l);
        for (MeasureDef m : script.getMeasures()) measure(m);
        for (io.github.llm4j.loom.ast.AgentDef a : script.getAgents()) agentDefault(a);
        for (DecisionDef d : script.getDecisions()) if (d.getLadder() != null && !d.isLadderFromTrust()) binding(d);
        for (LadderDef l : ladders.values()) {
            if (script.getDecisions().stream().noneMatch(d -> l.getName().equals(d.getLadder()))) {
                c.warn(l.getLine(), "ladder " + l.getName(), "no decision uses it (write ladder: " + l.getName() + " in a decision)");
            }
        }
    }

    /** Reports a problem once, however many bindings of a ladder make the same complaint. */
    private void error(int line, String construct, String message) {
        if (reported.add(line + "|" + construct + "|" + message)) c.error(line, construct, message);
    }

    private void warn(int line, String construct, String message) {
        if (reported.add("w|" + line + "|" + construct + "|" + message)) c.warn(line, construct, message);
    }

    // ---------- names ----------

    private void declarations() {
        Set<String> fieldNames = new HashSet<>(CaseFields.builtin().keySet());
        for (LadderDef l : script.getLadders()) {
            if (l.isSynthetic()) continue;                                  // written from a trust block: not the script's own
            if (ladders.putIfAbsent(l.getName(), l) != null) error(l.getLine(), "ladder " + l.getName(), "ladder " + l.getName() + " is already declared");
        }
        for (MeasureDef m : script.getMeasures()) {
            String who = "measure " + m.getName();
            if (measures.containsKey(m.getName())) {
                error(m.getLine(), who, "measure " + m.getName() + " is already declared");
                continue;
            }
            if (BUILT_IN_FUNCTIONS.contains(m.getName())) error(m.getLine(), who, m.getName() + " is a built-in function: choose another name");
            if (fieldNames.contains(m.getName())) error(m.getLine(), who, m.getName() + " has the same name as a field of the case: choose another name");
            measures.put(m.getName(), new Interpreter.Measure(m.getName(), m.getParam(), m.getBody()));
        }
    }

    /** Every field some decision remembers, for checking things that belong to no one decision. */
    private Map<String, T> allFields() {
        Map<String, T> f = CaseFields.builtin();
        for (DecisionDef d : script.getDecisions()) f.putAll(CaseFields.forDecision(d));
        return f;
    }

    private void measure(MeasureDef m) {
        Checker checker = new Checker(allFields(), Map.of(), measures, false, false);
        try {
            checker.measureType(m.getName());
        } catch (ExprException e) {
            error(m.getLine(), "measure " + m.getName(), e.getMessage());
        }
    }

    // ---------- a ladder ----------

    private void ladder(LadderDef l) {
        String who = "ladder " + l.getName();
        List<RungDef> rungs = l.getRungs();
        if (rungs.isEmpty()) {
            error(l.getLine(), who, "needs at least one rung, such as: rung watch { mode: blind }");
            return;
        }
        for (int i = 0; i < rungs.size(); i++) {
            RungDef r = rungs.get(i);
            String rung = who + ", rung " + r.getName();
            if (i == 0) {
                if (r.getClimb() != null) error(r.getClimbLine(), rung, "the first rung is where every scope starts, so it can't have a climb when");
                if (!r.getSlips().isEmpty()) error(r.getSlipLine(), rung, "the first rung has nowhere to slip down to, so it can't have a slip");
            } else if (r.getClimb() == null) {
                error(r.getLine(), rung, "can never be reached: it has no climb when <condition>");
            }
            for (RungDef.Slip sl : r.getSlips()) {
                if (sl.to() == null) continue;
                int to = l.indexOf(sl.to());
                if (to < 0) {
                    error(sl.line(), rung, "slip to " + sl.to() + ": no rung of that name in ladder " + l.getName() + Suggest.hint(sl.to(), rungNames(l)));
                } else if (to >= i) {
                    error(sl.line(), rung, "slip to " + sl.to() + " must go down: " + sl.to() + " is not below " + r.getName());
                }
            }
            if (r.getSamplePercent() != 0 || r.getSampleLine() != 0) {
                if (r.getSamplePercent() <= 0 || r.getSamplePercent() > 1) {
                    error(r.getSampleLine(), rung, "sample needs a percentage between 0% and 100% (not " + r.getSamplePercent() * 100 + "%)");
                } else if (r.getMode() == RungDef.Mode.BLIND) {
                    warn(r.getSampleLine(), rung, "sample does nothing on a blind rung: it already asks a person without the proposal");
                }
            }
            if (r.getEscalate() != null && r.getMode() == RungDef.Mode.BLIND) {
                warn(r.getEscalateLine(), rung, "escalate does nothing on a blind rung: it already asks a person");
            }
            if (r.getMode() == RungDef.Mode.AUTONOMOUS) {
                if (r.getSlips().isEmpty()) warn(r.getLine(), rung, "has no slip: once the agent is here, nothing takes it back down");
                if (r.getSamplePercent() <= 0) warn(r.getLine(), rung, "has no sample: nobody checks its cases blind, so its record can't be tested");
            }
        }
        if (rungs.stream().noneMatch(r -> r.getMode() == RungDef.Mode.AUTONOMOUS)) {
            warn(l.getLine(), who, "no rung is autonomous, so an agent on this ladder can never act on its own: add a rung with mode: autonomous (it may be hard to reach, but it should be there)");
        }
        if (rungs.size() > 1 && !l.isClimbingStated()) {
            error(l.getLine(), who, "say who approves climbing: climbing needs approval from NAME, or: climbing is automatic");
        }
    }

    private static List<String> rungNames(LadderDef l) {
        List<String> names = new ArrayList<>();
        for (RungDef r : l.getRungs()) names.add(r.getName());
        return names;
    }

    /** Checks the conditions of a ladder as seen by one decision (its fields) and the values it binds. */
    private void conditions(LadderDef l, Map<String, T> fields, Map<String, T> params, Map<String, Interpreter.Measure> ms) {
        Checker climbing = new Checker(fields, params, ms, true, false);
        Checker escalating = new Checker(fields, params, ms, true, true);
        for (RungDef r : l.getRungs()) {
            String rung = "ladder " + l.getName() + ", rung " + r.getName();
            condition(climbing, r.getClimb(), r.getClimbText(), r.getClimbLine(), rung + ", climb when");
            for (RungDef.Slip sl : r.getSlips()) condition(climbing, sl.expr(), sl.text(), sl.line(), rung + ", slip when");
            condition(escalating, r.getEscalate(), r.getEscalateText(), r.getEscalateLine(), rung + ", escalate when");
        }
    }

    private void condition(Checker checker, Expr e, String text, int line, String construct) {
        if (e == null) return;
        try {
            checker.condition(e);
        } catch (ExprException ex) {
            error(line, construct, ex.getMessage() + " (in: " + text + ")");
        }
    }

    // ---------- an agent's default ----------

    private void agentDefault(io.github.llm4j.loom.ast.AgentDef a) {
        String who = "agent " + a.getName();
        if (a.getLadder() == null) {
            if (a.getCeilingRung() != null) error(a.getCeilingRungLine(), who, "ceiling: needs a ladder: to apply to (write ladder: Name in the agent)");
            return;
        }
        LadderDef l = ladders.get(a.getLadder());
        if (l == null) {
            error(a.getLadderLine(), who, "no ladder named " + a.getLadder() + Suggest.hint(a.getLadder(), ladders.keySet()));
            return;
        }
        if (a.getCeilingRung() != null && l.rung(a.getCeilingRung()) == null) {
            error(a.getCeilingRungLine(), who, "ceiling: no rung named " + a.getCeilingRung() + " in ladder " + l.getName() + Suggest.hint(a.getCeilingRung(), rungNames(l)));
        }
        if (script.getDecisions().stream().noneMatch(d -> a.getName().equals(d.getAgent()))) {
            warn(a.getLadderLine(), who, "its default ladder " + l.getName() + " applies to no decision (no decision is proposed by " + a.getName() + ")");
        }
    }

    // ---------- a binding ----------

    private void binding(DecisionDef d) {
        String who = "decision " + d.getName();
        LadderDef l = ladders.get(d.getLadder());
        if (l == null) {
            error(d.getLadderLine(), who, "no ladder named " + d.getLadder() + Suggest.hint(d.getLadder(), ladders.keySet()));
            return;
        }
        if (d.isTrustGiven()) {
            error(d.getLine(), who, "has both a trust { } block and ladder: " + l.getName() + " (line " + d.getLadderLine() + "): keep the rules in one place");
        }

        Map<String, Interpreter.Measure> ms = new LinkedHashMap<>(measures);
        Interpreter.Measure dangerous = CaseFields.dangerous(d);
        if (dangerous != null && !ms.containsKey("dangerous")) ms.put("dangerous", dangerous);
        Map<String, T> fields = CaseFields.forDecision(d);
        for (String remembered : d.getRemember()) {
            if (measures.containsKey(remembered)) {
                error(d.getLine(), who, "remember: " + remembered + " has the same name as a measure: choose another name for one of them");
            }
        }

        Map<String, T> params = params(d, l, who, fields, ms);
        conditions(l, fields, params, ms);

        if (d.getCeilingRung() != null && l.rung(d.getCeilingRung()) == null) {
            error(d.getCeilingRungLine(), who, "ceiling: no rung named " + d.getCeilingRung() + " in ladder " + l.getName() + Suggest.hint(d.getCeilingRung(), rungNames(l)));
        }

        if (d.getProposes() != null && !d.getChoices().isEmpty()) {
            error(d.getLine(), who, "a decision either chooses (choices:) or proposes (proposes:), not both");
        }
        if (!d.isOnChangeStated()) {
            warn(d.getLine(), who, "no \"when the agent changes:\" line: a changed agent starts over (write when the agent changes: start over, to say so)");
        }
        anchoring(l);
        retention(d, l, who);
        if (d.getOnChange() == DecisionDef.OnChange.KEEP_TRUST) {
            int first = firstAutonomous(l);
            int ceiling = d.getCeilingRung() == null ? l.getRungs().size() - 1 : Math.max(0, l.indexOf(d.getCeilingRung()));
            if (first >= 0 && ceiling >= first) {
                error(d.getLine(), who, "keep the trust would let a new agent act on an old agent's record, and rung " + l.getRungs().get(first).getName()
                        + " is autonomous and can be reached: lower the ceiling below it, or write when the agent changes: start over");
            }
        }
    }

    /**
     * A climb into an autonomous rung that rests on cases a person answered while looking at the proposal: with no blind rung before it and no filter
     * on the cases, a reviewer who tends to agree with whatever is put in front of them could earn the agent the right to act alone.
     */
    private void anchoring(LadderDef l) {
        List<RungDef> rungs = l.getRungs();
        for (int i = 1; i < rungs.size(); i++) {
            RungDef r = rungs.get(i);
            if (r.getMode() != RungDef.Mode.AUTONOMOUS || r.getClimb() == null || rungs.get(i - 1).getMode() != RungDef.Mode.CONFIRM) continue;
            boolean blindBefore = rungs.subList(0, i).stream().anyMatch(x -> x.getMode() == RungDef.Mode.BLIND);
            if (blindBefore) continue;
            boolean[] filtered = {false};
            walk(r.getClimb(), e -> {
                if (e instanceof Expr.Call c && c.method().equals("where")) filtered[0] = true;
                if (e instanceof Expr.Name n && (n.name().equals("blind") || n.name().equals("shown"))) filtered[0] = true;
            });
            if (!filtered[0]) {
                warn(r.getClimbLine(), "ladder " + l.getName() + ", rung " + r.getName(), "climb when counts every case, including ones where the person saw the proposal and "
                        + "tends to agree with it: select the cases that are solid evidence, such as history.where(careful) or history.where(blind)");
            }
        }
    }

    /** A ladder that looks further back than the records are kept can never see the evidence it asks for. */
    private void retention(DecisionDef d, LadderDef l, String who) {
        if (d.getKeepDays() <= 0) return;
        long keepMs = d.getKeepDays() * 86_400_000L;
        for (RungDef r : l.getRungs()) {
            List<Expr> looked = new ArrayList<>();
            looked.add(r.getClimb());
            r.getSlips().forEach(sl -> looked.add(sl.expr()));
            looked.add(r.getEscalate());
            for (Expr e : looked) {
                if (e == null) continue;
                walk(e, x -> {
                    if (x instanceof Expr.Call c && (c.method().equals("since") || c.method().equals("between")) && !c.args().isEmpty()
                            && c.args().get(0) instanceof Expr.Lit lit && lit.value() instanceof io.github.llm4j.loom.autonomy.expr.V.Dur dur && dur.millis() > keepMs) {
                        warn(d.getLine(), who, "keep records for " + d.getKeepDays() + " days, but ladder " + l.getName() + " looks back " + lit.text() + " (rung " + r.getName()
                                + "): the older cases are gone, so that condition can never be met");
                    }
                });
            }
        }
    }

    private static void walk(Expr e, java.util.function.Consumer<Expr> visit) {
        visit.accept(e);
        if (e instanceof Expr.Field f) walk(f.target(), visit);
        else if (e instanceof Expr.Call c) {
            walk(c.target(), visit);
            c.args().forEach(a -> walk(a, visit));
        } else if (e instanceof Expr.Fn f) f.args().forEach(a -> walk(a, visit));
        else if (e instanceof Expr.Bin b) {
            walk(b.left(), visit);
            walk(b.right(), visit);
        } else if (e instanceof Expr.Not n) walk(n.operand(), visit);
        else if (e instanceof Expr.Neg n) walk(n.operand(), visit);
        else if (e instanceof Expr.ListLit l) l.items().forEach(a -> walk(a, visit));
    }

    private static int firstAutonomous(LadderDef l) {
        for (int i = 0; i < l.getRungs().size(); i++) if (l.getRungs().get(i).getMode() == RungDef.Mode.AUTONOMOUS) return i;
        return -1;
    }

    /** The parameter types from what the binding supplies, after checking the binding supplies exactly what the ladder asks for. */
    private Map<String, T> params(DecisionDef d, LadderDef l, String who, Map<String, T> fields, Map<String, Interpreter.Measure> ms) {
        Map<String, T> types = new LinkedHashMap<>();
        int line = d.getLadderLine();
        for (String p : l.getParams()) {
            Expr arg = d.getLadderArgs().get(p);
            if (arg == null) {
                error(line, who, "ladder " + l.getName() + " needs a value for " + p + ": ladder: " + l.getName() + "(" + p + ": …)");
                types.put(p, T.ANY);
                continue;
            }
            try {
                types.put(p, new Checker(fields, Map.of(), ms, false, false).type(arg));
            } catch (ExprException e) {
                error(line, who, "the value of " + p + ": " + e.getMessage());
                types.put(p, T.ANY);
            }
        }
        for (String given : d.getLadderArgs().keySet()) {
            if (!l.getParams().contains(given)) {
                error(line, who, given + " is not a parameter of ladder " + l.getName() + Suggest.hint(given, l.getParams())
                        + (l.getParams().isEmpty() ? "" : " (it takes: " + String.join(", ", l.getParams()) + ")"));
            }
        }
        return types;
    }
}
