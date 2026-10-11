package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.autonomy.Rec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds the records of a decision into cases. A case is its records in order: the case record opens it, a proposal and a response complete it,
 * outcomes arrive later. A case decided again by a later generation of its run (a rewind) keeps both; only the later one counts.
 */
public final class LedgerFold {

    private LedgerFold() { }

    private static final class Draft {
        Rec open;
        Rec proposed;
        Rec responded;
        final List<Rec> outcomes = new ArrayList<>();
    }

    public static List<LCase> cases(List<Rec> records) {
        Map<String, Draft> drafts = new LinkedHashMap<>();
        Map<String, Integer> latest = new LinkedHashMap<>();
        for (Rec r : records) {
            if (Rec.CASE.equals(r.kind())) {
                drafts.computeIfAbsent(r.caseId() + "#" + r.generation(), k -> new Draft()).open = r;
                latest.merge(r.caseId(), r.generation(), Math::max);
            }
        }
        for (Rec r : records) {
            if (r.kind().equals(Rec.CASE)) continue;
            if (r.kind().equals(Rec.PROPOSED) || r.kind().equals(Rec.RESPONDED)) {
                Draft d = drafts.get(r.caseId() + "#" + r.generation());
                if (d == null) continue;
                if (r.kind().equals(Rec.PROPOSED)) d.proposed = r;
                else d.responded = r;
            } else if (r.kind().equals(Rec.OUTCOME) && latest.containsKey(r.caseId())) {
                drafts.get(r.caseId() + "#" + latest.get(r.caseId())).outcomes.add(r);
            }
        }
        List<LCase> out = new ArrayList<>();
        for (Draft d : drafts.values()) {
            if (d.open != null) out.add(build(d, d.open.generation() < latest.get(d.open.caseId())));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static LCase build(Draft d, boolean superseded) {
        Rec o = d.open;
        Map<String, String> fields = new LinkedHashMap<>();
        if (o.body().get("fields") instanceof Map<?, ?> m) m.forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
        Rec p = d.proposed;
        Rec r = d.responded;
        List<String> flags = p != null && p.body().get("flags") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        Long millis = r == null ? null : r.num("millis") != null ? Long.valueOf(r.num("millis").longValue())
                : p != null ? Long.valueOf(Math.max(0, r.at().toEpochMilli() - p.at().toEpochMilli())) : null;
        List<LCase.Outcome> outcomes = new ArrayList<>();
        for (Rec x : d.outcomes) outcomes.add(new LCase.Outcome(x.str("result"), x.body().get("value"), x.at()));
        Number epoch = o.num("epoch");
        Number confidence = p == null ? null : p.num("confidence");
        return new LCase(o.caseId(), o.decision(), o.str("scope") == null ? "all" : o.str("scope"), o.at(), o.str("level"), o.str("how"), o.str("identity"),
                epoch == null ? 1 : epoch.intValue(), o.generation(), superseded, fields, o.str("servedModel"),
                p == null ? null : p.str("choice"), p == null ? null : p.str("value"), p == null ? null : p.str("reasoning"),
                confidence == null ? null : confidence.doubleValue(), p != null && p.flag("malformed"), p == null ? null : p.at(), flags,
                r != null, r == null ? null : r.str("kind"), r == null ? null : r.str("choice"), r == null ? null : r.str("final"),
                r == null ? null : r.str("by"), r != null && r.flag("shown"), millis, r == null ? (Instant) null : r.at(), outcomes);
    }
}
