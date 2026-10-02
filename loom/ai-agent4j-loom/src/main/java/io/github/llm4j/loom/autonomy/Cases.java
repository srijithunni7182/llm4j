package io.github.llm4j.loom.autonomy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Folds ledger records into cases: the highest generation of a run is the case's current decision, earlier ones are superseded. */
public final class Cases {

    private Cases() { }

    /** The cases of these records, in the order they were opened. Records about a case that was never opened are folded as an orphan, not dropped. */
    public static List<Case> fold(List<Rec> records) {
        Map<String, Map<Integer, List<Rec>>> byCase = new LinkedHashMap<>();
        for (Rec r : records) {
            if (r.caseId().isEmpty()) continue;
            byCase.computeIfAbsent(r.caseId(), k -> new LinkedHashMap<>()).computeIfAbsent(r.generation(), k -> new ArrayList<>()).add(r);
        }
        List<Case> out = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, List<Rec>>> e : byCase.entrySet()) {
            int highest = e.getValue().keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
            List<Rec> outcomes = new ArrayList<>();
            for (Map.Entry<Integer, List<Rec>> g : e.getValue().entrySet()) {
                for (Rec r : g.getValue()) if (Rec.OUTCOME.equals(r.kind())) outcomes.add(r);
            }
            for (Map.Entry<Integer, List<Rec>> g : e.getValue().entrySet()) {
                Case c = foldOne(e.getKey(), g.getKey(), g.getValue(), outcomes, g.getKey() < highest);
                if (c != null) out.add(c);
            }
        }
        return out;
    }

    /** The current (highest-generation) case of each id, superseded ones left out. */
    public static List<Case> current(List<Case> cases) {
        return cases.stream().filter(c -> !c.superseded()).toList();
    }

    private static Case foldOne(String id, int generation, List<Rec> records, List<Rec> outcomes, boolean superseded) {
        Rec opened = null, proposed = null, decided = null;
        for (Rec r : records) {
            switch (r.kind()) {
                case Rec.CASE -> opened = r;
                case Rec.PROPOSED -> proposed = r;
                case Rec.DECIDED -> decided = r;
                default -> { }
            }
        }
        Rec head = opened != null ? opened : records.get(0);
        Map<String, String> fields = new LinkedHashMap<>();
        if (opened != null && opened.body().get("fields") instanceof Map<?, ?> m) m.forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
        List<String> flags = new ArrayList<>();
        if (opened != null && opened.body().get("flags") instanceof List<?> l) l.forEach(f -> flags.add(String.valueOf(f)));
        if (proposed != null && proposed.body().get("flags") instanceof List<?> l) l.forEach(f -> { if (!flags.contains(String.valueOf(f))) flags.add(String.valueOf(f)); });
        if ((opened != null && opened.flag("purged")) || (proposed != null && proposed.flag("purged"))) flags.add("fields_masked");
        List<String> results = new ArrayList<>();
        for (Rec o : outcomes) results.add(o.str("result"));
        Instant decidedAt = decided == null ? null : decided.at();
        return new Case(id, head.decision(), opened == null ? "" : opened.str("scope") == null ? "" : opened.str("scope"),
                opened == null ? null : opened.str("locator"), opened == null ? null : opened.str("step"), opened == null ? null : opened.str("journalStep"), head.at(), fields,
                opened == null ? null : Level.of(opened.str("level")), opened == null ? null : opened.str("identity"),
                opened == null || opened.num("epoch") == null ? 1 : opened.num("epoch").intValue(), generation, superseded, flags,
                proposed == null ? null : proposed.str("choice"), proposed == null ? null : proposed.str("reasoning"),
                proposed == null || proposed.num("confidence") == null ? null : proposed.num("confidence").doubleValue(),
                proposed != null && proposed.flag("malformed"),
                decided == null ? null : decided.str("verdict"), decided == null ? null : decided.str("decider"),
                decided != null && decided.flag("shown"), decidedAt, decided == null || decided.num("millis") == null ? null : decided.num("millis").longValue(),
                results);
    }
}
