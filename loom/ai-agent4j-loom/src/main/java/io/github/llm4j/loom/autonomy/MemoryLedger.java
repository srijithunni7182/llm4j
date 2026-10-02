package io.github.llm4j.loom.autonomy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A ledger in memory: tests and one-shot runs. */
public class MemoryLedger implements Ledger {

    private final Map<String, List<Rec>> records = new LinkedHashMap<>();
    private final Map<String, Set<String>> ids = new LinkedHashMap<>();

    @Override
    public synchronized void append(Rec record) {
        if (!ids.computeIfAbsent(record.decision(), k -> new HashSet<>()).add(record.id())) return;
        records.computeIfAbsent(record.decision(), k -> new ArrayList<>()).add(record);
    }

    @Override
    public synchronized List<Rec> records(String decision) {
        return List.copyOf(records.getOrDefault(decision, List.of()));
    }

    @Override
    public synchronized void purgeFields(String decision, Instant before) {
        List<Rec> list = records.get(decision);
        if (list == null) return;
        list.replaceAll(r -> Purge.apply(r, before));
    }
}
