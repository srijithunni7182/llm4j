package io.github.llm4j.loom.trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A store that lives as long as the process — for tests and hosts that don't need durability. */
public class InMemoryTriggerStore implements TriggerStore {

    private final Map<String, Trigger> triggers = new ConcurrentHashMap<>();

    @Override
    public synchronized void upsert(Trigger trigger) {
        triggers.put(trigger.id(), trigger.withClaim(null, null));
    }

    @Override
    public Optional<Trigger> get(String id) {
        return Optional.ofNullable(triggers.get(id));
    }

    @Override
    public List<Trigger> all() {
        return new ArrayList<>(triggers.values());
    }

    @Override
    public synchronized void remove(String id) {
        triggers.remove(id);
    }

    @Override
    public synchronized boolean claim(String id, String owner, Instant now, Duration staleAfter) {
        Trigger t = triggers.get(id);
        if (t == null || !t.enabled() || t.nextFire() == null || t.nextFire().isAfter(now)) return false;
        if (t.claimedBy() != null && t.claimedAt() != null && t.claimedAt().isAfter(now.minus(staleAfter))) return false;
        triggers.put(id, t.withClaim(owner, now));
        return true;
    }

    @Override
    public synchronized void complete(String id, String owner, Trigger next) {
        Trigger t = triggers.get(id);
        if (t == null || !owner.equals(t.claimedBy())) return;
        if (next == null) triggers.remove(id);
        else triggers.put(id, next.withClaim(null, null));
    }
}
