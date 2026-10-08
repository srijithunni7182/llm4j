package io.github.llm4j.loom.trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The durable home of every {@link Trigger}: resumes of paused runs and scheduled workflows. The store is
 * the truth — whatever wakes Loom (an embedded runner, {@code weave tick} from cron or systemd, a cloud
 * scheduler calling an endpoint) only asks it what is due.
 *
 * <p>Firing is claimed first, atomically, so that with several processes or machines on one store each
 * trigger fires once. A claim older than {@code staleAfter} (its process died) may be taken over.
 * Writing a trigger with {@link #upsert} clears any claim on it: a run that pauses again replaces its own
 * resume trigger while it is being fired.
 */
public interface TriggerStore {

    /** Adds or replaces a trigger (by id), clearing any claim. */
    void upsert(Trigger trigger);

    Optional<Trigger> get(String id);

    List<Trigger> all();

    void remove(String id);

    /**
     * Claims a due, enabled trigger for {@code owner}. True if this caller now owns the firing; false if it
     * is not due, disabled, gone, or claimed by someone else less than {@code staleAfter} ago.
     */
    boolean claim(String id, String owner, Instant now, Duration staleAfter);

    /**
     * Finishes a firing: replaces the trigger with {@code next} (or removes it when null) — but only if
     * {@code owner} still holds the claim; if the trigger was rewritten meanwhile, the new one stands.
     */
    void complete(String id, String owner, Trigger next);

    /** Enabled triggers due at {@code now}, earliest first (claimed ones included; claiming decides). */
    default List<Trigger> due(Instant now) {
        return all().stream()
                .filter(t -> t.enabled() && t.nextFire() != null && !t.nextFire().isAfter(now))
                .sorted(Comparator.comparing(Trigger::nextFire))
                .toList();
    }

    /** The earliest time any enabled trigger is due, if any. */
    default Optional<Instant> nextDue() {
        return all().stream().filter(t -> t.enabled() && t.nextFire() != null)
                .map(Trigger::nextFire).min(Comparator.naturalOrder());
    }

    /**
     * Serialises ticks across processes where the store can (a file lock). Returns null if another tick
     * holds it — that tick will fire what is due.
     */
    default AutoCloseable tickLock() {
        return () -> { };
    }
}
