package io.github.llm4j.loom.trigger;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Fires due triggers. Use {@link #tick()} from anything that wakes Loom — {@code weave tick} run by cron
 * or systemd, an HTTP endpoint called by a cloud scheduler — or {@link #start} for an embedded loop.
 *
 * <p>Rules: a one-off ({@code AT}) trigger is removed once its target is done, failed or waiting for a
 * person; a paused run's harness has already replaced it with the next resume. A schedule moves to its
 * next slot after now; if it missed slots while nothing ran it fires once ({@code run_once}) or not at
 * all ({@code skip}); with {@code overlap: skip} it does not start a run while an earlier run of the same
 * schedule is still paused.
 */
public class TriggerRunner {

    private static final Logger log = Logger.getLogger(TriggerRunner.class.getName());

    /** A record of one firing, for audit logs. */
    public record Fired(Trigger trigger, String runId, TriggerTarget.Outcome outcome, long lateMillis) { }

    private final TriggerStore store;
    private final TriggerTarget target;
    private final Clock clock;
    private final String owner;
    private Duration staleAfter = Duration.ofMinutes(10);
    private final List<Consumer<Fired>> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService loop;

    public TriggerRunner(TriggerStore store, TriggerTarget target, Clock clock) {
        this(store, target, clock, defaultOwner());
    }

    public TriggerRunner(TriggerStore store, TriggerTarget target, Clock clock, String owner) {
        this.store = store;
        this.target = target;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.owner = owner;
    }

    static String defaultOwner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "host";
        }
        return host + "/" + ProcessHandle.current().pid() + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A claim older than this belongs to a process that died; it may be taken over. Default 10 minutes. */
    public TriggerRunner staleAfter(Duration d) {
        this.staleAfter = d;
        return this;
    }

    public TriggerRunner onFired(Consumer<Fired> listener) {
        listeners.add(listener);
        return this;
    }

    public String owner() {
        return owner;
    }

    /** Fires everything due now; returns how many triggers were fired (skips count). */
    public int tick() {
        AutoCloseable lock = store.tickLock();
        if (lock == null) return 0; // another tick is firing what is due
        int fired = 0;
        try (lock) {
            for (Trigger due : store.due(clock.instant())) {
                Instant now = clock.instant();
                if (!store.claim(due.id(), owner, now, staleAfter)) continue;
                Trigger t = store.get(due.id()).orElse(null);
                if (t == null) continue;
                fire(t, now);
                fired++;
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e);
        }
        return fired;
    }

    private void fire(Trigger t, Instant now) {
        long late = Math.max(0, now.toEpochMilli() - t.nextFire().toEpochMilli());
        String runId = null;
        TriggerTarget.Outcome outcome;
        Trigger next;
        if (t.kind() == Trigger.Kind.AT) {
            runId = t.target() instanceof Trigger.ResumeRun r ? r.runId() : null;
            outcome = call(t, runId);
            next = outcome.status() == TriggerTarget.Outcome.Status.SUSPENDED && outcome.resumeAt() != null
                    // only used if the harness did not replace the trigger itself
                    ? Trigger.resume(runId, outcome.resumeAt(), outcome.message(), t.attempts() + 1)
                    : null;
        } else {
            Instant slot = t.nextFire();
            Instant following = t.slotAfter(now);
            boolean missed = !t.slotAfter(slot).isAfter(now);
            if (t.target() instanceof Trigger.StartWorkflow) runId = scheduleName(t) + "@" + slot;
            if (missed && t.misfire() == Trigger.Misfire.SKIP) {
                outcome = new TriggerTarget.Outcome(TriggerTarget.Outcome.Status.SKIPPED, null, "missed slot " + slot);
            } else if (t.overlap() == Trigger.Overlap.SKIP && earlierRunPaused(t)) {
                outcome = new TriggerTarget.Outcome(TriggerTarget.Outcome.Status.SKIPPED_OVERLAP, null,
                        "an earlier run of " + scheduleName(t) + " is still paused");
            } else {
                outcome = call(t, runId);
            }
            next = t.fired(now, outcome.toString(), following);
        }
        store.complete(t.id(), owner, next);
        log.info("Trigger " + t.id() + " fired: " + outcome);
        Fired record = new Fired(t, runId, outcome, late);
        for (Consumer<Fired> l : listeners) {
            try {
                l.accept(record);
            } catch (RuntimeException e) {
                log.warning("Trigger listener failed: " + e.getMessage());
            }
        }
    }

    private TriggerTarget.Outcome call(Trigger t, String runId) {
        try {
            TriggerTarget.Outcome o = target.fire(t, runId);
            return o != null ? o : TriggerTarget.Outcome.done();
        } catch (Exception e) {
            return TriggerTarget.Outcome.failed(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /** The schedule's name: its id after the last '/' ({@code schedule:<script>/<name>}). */
    static String scheduleName(Trigger t) {
        String id = t.id();
        return id.substring(id.lastIndexOf('/') + 1);
    }

    private boolean earlierRunPaused(Trigger t) {
        String prefix = Trigger.resumeId(scheduleName(t) + "@");
        return store.all().stream().anyMatch(o -> o.id().startsWith(prefix) && o.enabled());
    }

    /** Starts an embedded loop: fires what is overdue now, then polls every {@code every}. */
    public synchronized void start(Duration every) {
        if (loop != null) return;
        loop = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "loom-triggers");
            th.setDaemon(true);
            return th;
        });
        loop.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                log.warning("Trigger tick failed: " + e.getMessage());
            }
        }, 0, every.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (loop != null) {
            loop.shutdownNow();
            loop = null;
        }
    }
}
