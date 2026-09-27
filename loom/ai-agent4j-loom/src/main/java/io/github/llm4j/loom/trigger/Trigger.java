package io.github.llm4j.loom.trigger;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;

/**
 * A persisted rule that fires a target at a time: once ({@link Kind#AT}, e.g. resuming a paused run),
 * on a fixed interval ({@link Kind#EVERY}) or on a cron schedule ({@link Kind#CRON}). Immutable; stores
 * keep them durably so nothing is lost when no process is running.
 *
 * @param id          {@code resume:<runId>} or {@code schedule:<script>/<name>}
 * @param spec        AT: unused; EVERY: an ISO-8601 duration ({@code PT6H}); CRON: a 5-field expression
 * @param zone        the time zone cron fields are read in
 * @param nextFire    when it is next due (always on a slot for EVERY and CRON)
 * @param note        why it exists, e.g. the limit a run is waiting for
 * @param attempts    how many times it has fired (AT: which resume this is)
 * @param claimedBy   the instance firing it right now, or null
 */
public record Trigger(
        String id,
        Kind kind,
        String spec,
        ZoneId zone,
        Target target,
        Instant nextFire,
        Instant lastFire,
        String lastOutcome,
        String note,
        int attempts,
        Misfire misfire,
        Overlap overlap,
        boolean enabled,
        String claimedBy,
        Instant claimedAt) {

    public enum Kind { AT, EVERY, CRON }

    /** A schedule that missed slots while nothing ran: fire once now, or skip to the next slot. */
    public enum Misfire { RUN_ONCE, SKIP }

    /** A scheduled workflow whose previous run is still paused: skip this slot, or start another run anyway. */
    public enum Overlap { SKIP, QUEUE }

    /** What a trigger fires. */
    public sealed interface Target permits ResumeRun, StartWorkflow, AgentTask { }

    /** Resume a paused run. */
    public record ResumeRun(String runId) implements Target { }

    /** Start a new run of a workflow in a script. */
    public record StartWorkflow(String script, String workflow, Map<String, String> args) implements Target {
        public StartWorkflow {
            args = args == null ? Map.of() : Map.copyOf(args);
        }
    }

    /** Give an agent of a script one task (a classic {@code schedule} block). */
    public record AgentTask(String script, String agent, String task) implements Target { }

    public Trigger {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        if (zone == null) zone = ZoneOffset.UTC;
        if (misfire == null) misfire = Misfire.RUN_ONCE;
        if (overlap == null) overlap = Overlap.SKIP;
    }

    /** The trigger that resumes {@code runId} at {@code at}. */
    public static Trigger resume(String runId, Instant at, String note, int attempts) {
        return new Trigger(resumeId(runId), Kind.AT, null, null, new ResumeRun(runId), at, null, null, note,
                attempts, Misfire.RUN_ONCE, Overlap.QUEUE, true, null, null);
    }

    public static String resumeId(String runId) {
        return "resume:" + runId;
    }

    public static Trigger every(String id, Duration period, Instant first, Target target, Misfire misfire, Overlap overlap) {
        if (period.isZero() || period.isNegative()) throw new IllegalArgumentException("every: period must be positive");
        return new Trigger(id, Kind.EVERY, period.toString(), null, target, first, null, null, null, 0, misfire, overlap,
                true, null, null);
    }

    public static Trigger cron(String id, String expression, ZoneId zone, Instant now, Target target, Misfire misfire,
                               Overlap overlap) {
        CronSchedule cron = CronSchedule.parse(expression);
        return new Trigger(id, Kind.CRON, expression, zone, target, cron.next(now, zone == null ? ZoneOffset.UTC : zone),
                null, null, null, 0, misfire, overlap, true, null, null);
    }

    /** The first slot strictly after {@code t}; null for AT. */
    public Instant slotAfter(Instant t) {
        return switch (kind) {
            case AT -> null;
            case EVERY -> {
                Duration period = Duration.parse(spec);
                if (t.isBefore(nextFire)) yield nextFire;
                long behind = Duration.between(nextFire, t).toMillis() / period.toMillis() + 1;
                yield nextFire.plus(period.multipliedBy(behind));
            }
            case CRON -> CronSchedule.parse(spec).next(t, zone);
        };
    }

    public Trigger withNextFire(Instant next) {
        return new Trigger(id, kind, spec, zone, target, next, lastFire, lastOutcome, note, attempts, misfire, overlap,
                enabled, claimedBy, claimedAt);
    }

    public Trigger fired(Instant at, String outcome, Instant next) {
        return new Trigger(id, kind, spec, zone, target, next, at, outcome, note, attempts + 1, misfire, overlap,
                enabled, null, null);
    }

    public Trigger withEnabled(boolean on) {
        return new Trigger(id, kind, spec, zone, target, nextFire, lastFire, lastOutcome, note, attempts, misfire,
                overlap, on, claimedBy, claimedAt);
    }

    public Trigger withClaim(String owner, Instant at) {
        return new Trigger(id, kind, spec, zone, target, nextFire, lastFire, lastOutcome, note, attempts, misfire,
                overlap, enabled, owner, at);
    }

    /** Same rule and target (what a script edit would change). */
    public boolean sameRule(Trigger other) {
        return kind == other.kind && Objects.equals(spec, other.spec) && Objects.equals(zone, other.zone)
                && target.equals(other.target) && misfire == other.misfire && overlap == other.overlap;
    }

    /** A short human description, e.g. {@code cron "0 7 * * *" Asia/Kolkata}. */
    public String describe() {
        return switch (kind) {
            case AT -> "at " + nextFire;
            case EVERY -> "every " + spec.substring(2).toLowerCase();
            case CRON -> "cron \"" + spec + "\" " + zone;
        };
    }
}
