package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.autonomy.expr.Checker.T;
import io.github.llm4j.loom.autonomy.expr.ExprParser;
import io.github.llm4j.loom.autonomy.expr.Interpreter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an expression can read from a case. One definition, used both to check expressions when a script loads and to build the case the
 * interpreter sees, so the two cannot drift apart.
 */
public final class CaseFields {

    private CaseFields() { }

    /** The fields every case has, and their types. Parts of a record ({@code response.kind}) are not described and may be anything. */
    public static Map<String, T> builtin() {
        Map<String, T> f = new LinkedHashMap<>();
        f.put("id", T.STR);
        f.put("at", T.NUM);
        f.put("scope", T.STR);
        f.put("epoch", T.NUM);
        f.put("level", T.STR);
        f.put("proposal", T.ANY);
        f.put("response", T.ANY);
        f.put("outcome", T.STR);
        f.put("outcomes", T.ANY);
        f.put("shown", T.BOOL);
        f.put("blind", T.BOOL);
        f.put("proposed_at", T.NUM);
        f.put("responded_at", T.NUM);
        f.put("review_time", T.DUR);
        f.put("latency", T.DUR);
        return f;
    }

    /** The built-in fields and the values a decision remembers (and groups by), which have no declared type. */
    public static Map<String, T> forDecision(DecisionDef d) {
        Map<String, T> f = builtin();
        for (String name : d.getRemember()) f.putIfAbsent(name, declared(d.getRememberTypes().get(name)));
        if (d.getGroupBy() != null) f.putIfAbsent(d.getGroupBy(), T.ANY);
        return f;
    }

    /** The type a script declared for a remembered value; a value with none declared could be anything. */
    public static T declared(String word) {
        if (word == null) return T.ANY;
        switch (word) {
            case "number": return T.NUM;
            case "text": return T.STR;
            case "flag": return T.BOOL;
            default: return T.ANY;
        }
    }

    /**
     * {@code dangerous}: true for a case where the agent proposed what the decision calls a dangerous mistake and the person decided the
     * opposite. Generated from the decision's {@code dangerous mistake:} lines; null when there are none.
     */
    public static Interpreter.Measure dangerous(DecisionDef d) {
        if (d.getDangerous().isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (DecisionDef.Mistake m : d.getDangerous()) {
            if (b.length() > 0) b.append(" or ");
            b.append("(c.proposal.choice == \"").append(m.proposed()).append("\" and c.response.choice == \"").append(m.decided()).append("\")");
        }
        return new Interpreter.Measure("dangerous", "c", ExprParser.parse(b.toString()));
    }
}
