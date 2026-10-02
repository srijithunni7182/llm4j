package io.github.llm4j.loom.autonomy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a ledger implementation keeps in memory so that asking about cases is cheap however long the ledger is: the records in order, the ids
 * already seen, and the cases folded <i>incrementally</i> (only a case a new record touched is folded again). A ledger on disk or in a database
 * feeds it what is new since it last looked.
 */
final class LedgerCache {

    private final List<Rec> records = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();
    private final Map<String, Map<Integer, List<Rec>>> byCase = new LinkedHashMap<>();
    private final Map<String, List<Case>> folded = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();
    private final Map<String, List<Rec>> byKind = new HashMap<>();
    private List<Case> snapshot;

    boolean has(String id) {
        return ids.contains(id);
    }

    int size() {
        return records.size();
    }

    /** Adds a record; false when one with its id is already there. */
    boolean add(Rec r) {
        if (!ids.add(r.id())) return false;
        records.add(r);
        byKind.computeIfAbsent(r.kind(), k -> new ArrayList<>()).add(r);
        if (!r.caseId().isEmpty()) {
            byCase.computeIfAbsent(r.caseId(), k -> new LinkedHashMap<>()).computeIfAbsent(r.generation(), k -> new ArrayList<>()).add(r);
            dirty.add(r.caseId());
            snapshot = null;
        }
        return true;
    }

    List<Rec> records() {
        return List.copyOf(records);
    }

    List<Rec> ofKinds(String... kinds) {
        List<Rec> out = new ArrayList<>();
        for (String k : kinds) out.addAll(byKind.getOrDefault(k, List.of()));
        out.sort(java.util.Comparator.comparingInt(records::indexOf));
        return out;
    }

    /** The cases, in the order they were opened; the same list until a record about a case arrives. */
    List<Case> cases() {
        if (snapshot != null) return snapshot;
        for (String id : dirty) folded.put(id, Cases.foldCase(id, byCase.get(id)));
        dirty.clear();
        List<Case> out = new ArrayList<>();
        for (String id : byCase.keySet()) out.addAll(folded.get(id));
        snapshot = List.copyOf(out);
        return snapshot;
    }

    void clear() {
        records.clear();
        ids.clear();
        byCase.clear();
        folded.clear();
        dirty.clear();
        byKind.clear();
        snapshot = null;
    }
}
