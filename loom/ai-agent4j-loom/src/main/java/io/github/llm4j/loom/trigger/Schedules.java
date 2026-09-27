package io.github.llm4j.loom.trigger;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ScheduleDef;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Turns a script's {@code schedule} blocks into stored triggers, and other trigger helpers. */
public final class Schedules {

    private Schedules() { }

    public static String scheduleId(String scriptRef, String name) {
        return "schedule:" + scriptRef + "/" + name;
    }

    /** The trigger a schedule block describes, first due after {@code now}. */
    public static Trigger toTrigger(ScheduleDef sd, String scriptRef, Instant now) {
        String id = scheduleId(scriptRef, sd.getName());
        Trigger.Target target = sd.getRunWorkflow() != null
                ? new Trigger.StartWorkflow(scriptRef, sd.getRunWorkflow(), sd.getRunArgs())
                : new Trigger.AgentTask(scriptRef, sd.getAgentName(), sd.getTask());
        Trigger.Misfire misfire = sd.getMisfire() != null ? Trigger.Misfire.valueOf(sd.getMisfire().toUpperCase()) : null;
        Trigger.Overlap overlap = sd.getOverlap() != null ? Trigger.Overlap.valueOf(sd.getOverlap().toUpperCase()) : null;
        if (sd.getCron() != null) {
            ZoneId zone = sd.getTimezone() != null ? ZoneId.of(sd.getTimezone()) : ZoneId.of("UTC");
            return Trigger.cron(id, sd.getCron(), zone, now, target, misfire, overlap);
        }
        Duration period = sd.getEvery() != null ? sd.getEvery() : parse(sd.getPattern());
        if (period == null || period.isZero()) {
            throw new IllegalArgumentException("schedule " + sd.getName() + " needs cron or every");
        }
        Duration delay = sd.getInitialDelay() != null ? parse(sd.getInitialDelay()) : Duration.ZERO;
        return Trigger.every(id, period, now.plus(delay == null || delay.isZero() ? period : delay), target, misfire, overlap);
    }

    /**
     * Brings the store in line with the script: adds new schedules, updates changed ones (keeping when
     * they last fired), and disables — never deletes — schedules no longer in the script.
     *
     * @return the ids written
     */
    public static List<String> reconcile(LoomScript script, String scriptRef, TriggerStore store, Instant now) {
        List<String> written = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (ScheduleDef sd : script.getSchedules()) {
            Trigger wanted = toTrigger(sd, scriptRef, now);
            present.add(wanted.id());
            Trigger existing = store.get(wanted.id()).orElse(null);
            if (existing != null && existing.sameRule(wanted) && existing.enabled()) continue;
            Trigger merged = existing == null ? wanted
                    : new Trigger(wanted.id(), wanted.kind(), wanted.spec(), wanted.zone(), wanted.target(),
                            existing.sameRule(wanted) ? existing.nextFire() : wanted.nextFire(), existing.lastFire(),
                            existing.lastOutcome(), existing.note(), existing.attempts(), wanted.misfire(),
                            wanted.overlap(), true, null, null);
            store.upsert(merged);
            written.add(merged.id());
        }
        String prefix = "schedule:" + scriptRef + "/";
        for (Trigger t : store.all()) {
            if (t.id().startsWith(prefix) && !present.contains(t.id()) && t.enabled()) {
                store.upsert(t.withEnabled(false));
                written.add(t.id());
            }
        }
        return written;
    }

    /** Up to 10% of the wait, at most 30 s, so runs throttled together don't all come back at once. */
    public static Duration jitter(Duration wait) {
        long max = Math.min(30_000, Math.max(0, wait.toMillis() / 10));
        return max == 0 ? Duration.ZERO : Duration.ofMillis(ThreadLocalRandom.current().nextLong(max + 1));
    }

    /** {@code 30s}, {@code 15m}, {@code 6h}, {@code 2d}, or plain seconds; null if blank. */
    public static Duration parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().toLowerCase();
        long n = Long.parseLong(v.replaceAll("[smhd]$", ""));
        return switch (v.charAt(v.length() - 1)) {
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> Duration.ofSeconds(n);
        };
    }
}
