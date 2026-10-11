package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.autonomy.expr.V;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Cases as an expression sees them: the {@code history} of a scope, each case a record with the fields of {@link CaseFields}. */
public final class HistoryBuilder {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private HistoryBuilder() { }

    /**
     * The cases that count: answered, not superseded, of this scope, and of this epoch ({@code epoch < 0} takes every epoch). In the order they
     * were decided.
     */
    public static V.Cs history(List<LCase> all, String scope, int epoch) {
        List<V.Rec> out = new ArrayList<>();
        for (LCase c : all) {
            if (c.superseded() || !c.responded() || !c.scope().equals(scope)) continue;
            if (epoch >= 0 && c.epoch() != epoch) continue;
            out.add(record(c));
        }
        return V.cases(out);
    }

    /** A remembered value (kept as text in the ledger): a number if it is one, {@code true} or {@code false}, else the text. */
    static V typed(String s) {
        if (s == null) return V.UNDEFINED;
        if (NUMBER.matcher(s).matches()) return V.num(Double.parseDouble(s));
        if (s.equals("true")) return V.bool(true);
        if (s.equals("false")) return V.bool(false);
        return V.str(s);
    }

    public static V.Rec record(LCase c) {
        Map<String, V> f = new LinkedHashMap<>();
        c.fields().forEach((k, v) -> f.put(k, typed(v)));
        f.put("id", V.str(c.id()));
        f.put("at", V.num(c.at().toEpochMilli()));
        f.put("scope", V.str(c.scope()));
        f.put("epoch", V.num(c.epoch()));
        if (c.level() != null) f.put("level", V.str(c.level()));
        f.put("proposal", proposal(c));
        f.put("response", response(c));
        f.put("shown", V.bool(c.shown()));
        f.put("blind", V.bool(c.blind()));
        if (c.proposedAt() != null) f.put("proposed_at", V.num(c.proposedAt().toEpochMilli()));
        if (c.respondedAt() != null) f.put("responded_at", V.num(c.respondedAt().toEpochMilli()));
        if (c.reviewMillis() != null) {
            f.put("review_time", V.dur(c.reviewMillis()));
            f.put("latency", V.dur(c.reviewMillis()));
        }
        if (c.latestOutcome() != null) f.put("outcome", V.str(c.latestOutcome()));
        f.put("outcomes", outcomes(c));
        return V.rec(f);
    }

    private static V.Rec proposal(LCase c) {
        Map<String, V> m = new LinkedHashMap<>();
        if (c.proposalChoice() != null) m.put("choice", V.str(c.proposalChoice()));
        if (c.proposalValue() != null) m.put("value", V.str(c.proposalValue()));
        if (c.reasoning() != null) m.put("reasoning", V.str(c.reasoning()));
        if (c.confidence() != null) m.put("confidence", V.num(c.confidence()));
        return V.rec(m);
    }

    private static V.Rec response(LCase c) {
        Map<String, V> m = new LinkedHashMap<>();
        if (c.responseKind() != null) m.put("kind", V.str(c.responseKind()));
        if (c.responseChoice() != null) m.put("choice", V.str(c.responseChoice()));
        if (c.responseFinal() != null) m.put("final", V.str(c.responseFinal()));
        if (c.responder() != null) m.put("by", V.str(c.responder()));
        if (c.respondedAt() != null) m.put("at", V.num(c.respondedAt().toEpochMilli()));
        return V.rec(m);
    }

    private static V.Rec outcomes(LCase c) {
        Map<String, V> m = new LinkedHashMap<>();
        for (LCase.Outcome o : c.outcomes()) {
            Object v = o.value();
            V value = v == null ? V.bool(true) : v instanceof Number n ? V.num(n.doubleValue()) : v instanceof Boolean b ? V.bool(b) : typed(String.valueOf(v));
            m.put(o.name(), value);
        }
        return V.rec(m);
    }
}
