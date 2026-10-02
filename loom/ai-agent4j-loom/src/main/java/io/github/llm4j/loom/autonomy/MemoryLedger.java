package io.github.llm4j.loom.autonomy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A ledger in memory: tests and one-shot runs. */
public class MemoryLedger implements Ledger {

    private final Map<String, LedgerCache> caches = new LinkedHashMap<>();

    private LedgerCache cache(String decision) {
        return caches.computeIfAbsent(decision, k -> new LedgerCache());
    }

    @Override
    public synchronized void append(Rec record) {
        cache(record.decision()).add(record);
    }

    @Override
    public synchronized List<Rec> records(String decision) {
        return cache(decision).records();
    }

    @Override
    public synchronized List<Rec> recordsOfKind(String decision, String... kinds) {
        return cache(decision).ofKinds(kinds);
    }

    @Override
    public synchronized List<Case> cases(String decision) {
        return cache(decision).cases();
    }

    @Override
    public synchronized void purgeFields(String decision, Instant before) {
        LedgerCache cache = cache(decision);
        List<Rec> purged = new ArrayList<>();
        for (Rec r : cache.records()) purged.add(Purge.apply(r, before));
        cache.clear();
        purged.forEach(cache::add);
    }
}
