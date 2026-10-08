package io.github.llm4j.loom.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.resume.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verification plan V7.1–V7.5, V7.8–V7.13, V7.18 on the in-memory store. */
@Tag("fragile")
class TriggerRunnerTest {

    static final Trigger.Target DIGEST = new Trigger.StartWorkflow("digest.loom", "DailyDigest", Map.of("topic", "AI"));

    final TestClock clock = new TestClock();
    final TriggerStore store = new InMemoryTriggerStore();
    final List<String> fired = Collections.synchronizedList(new ArrayList<>());
    final List<TriggerRunner.Fired> records = Collections.synchronizedList(new ArrayList<>());
    BiFunction<Trigger, String, TriggerTarget.Outcome> answer = (t, run) -> TriggerTarget.Outcome.done();

    TriggerRunner runner() {
        return new TriggerRunner(store, (t, runId) -> {
            fired.add(t.id() + (runId != null ? " " + runId : ""));
            return answer.apply(t, runId);
        }, clock, "test").onFired(records::add);
    }

    @Test
    void v7_1_firesOnlyWhenDue() {
        store.upsert(Trigger.resume("r1", Instant.parse("2026-09-27T10:05:00Z"), "limit", 1));
        clock.set("2026-09-27T10:04:59Z");
        assertThat(runner().tick()).isZero();
        clock.set("2026-09-27T10:05:00Z");
        assertThat(runner().tick()).isEqualTo(1);
        assertThat(fired).containsExactly("resume:r1 r1");
        assertThat(runner().tick()).isZero(); // gone once done
    }

    @Test
    void v7_2_oneResumeTriggerPerRun() {
        store.upsert(Trigger.resume("r1", Instant.parse("2026-09-27T10:05:00Z"), "a", 1));
        store.upsert(Trigger.resume("r1", Instant.parse("2026-09-27T10:30:00Z"), "b", 1));
        assertThat(store.all()).singleElement().satisfies(t -> {
            assertThat(t.nextFire()).isEqualTo(Instant.parse("2026-09-27T10:30:00Z"));
            assertThat(t.note()).isEqualTo("b");
        });
    }

    @Test
    void v7_3_aRunThatPausesAgainGetsANewResume() {
        store.upsert(Trigger.resume("r1", TestClock.T0, "limit", 1));
        answer = (t, run) -> TriggerTarget.Outcome.suspended(Instant.parse("2026-09-27T12:00:00Z"), "again");
        runner().tick();
        assertThat(store.get("resume:r1")).get().satisfies(t -> {
            assertThat(t.nextFire()).isEqualTo(Instant.parse("2026-09-27T12:00:00Z"));
            assertThat(t.attempts()).isEqualTo(2);
            assertThat(t.claimedBy()).isNull();
        });
    }

    @Test
    void v7_3b_theHarnessReplacementWins() {
        store.upsert(Trigger.resume("r1", TestClock.T0, "limit", 1));
        answer = (t, run) -> {
            // what HarnessExecutor does when the resumed run pauses again (with its own jitter)
            store.upsert(Trigger.resume("r1", Instant.parse("2026-09-27T12:00:07Z"), "harness", 2));
            return TriggerTarget.Outcome.suspended(Instant.parse("2026-09-27T12:00:00Z"), "again");
        };
        runner().tick();
        assertThat(store.get("resume:r1").orElseThrow().nextFire()).isEqualTo(Instant.parse("2026-09-27T12:00:07Z"));
        assertThat(store.get("resume:r1").orElseThrow().note()).isEqualTo("harness");
    }

    @Test
    void v7_4_doneRemovesAResumeAndAdvancesASchedule() {
        store.upsert(Trigger.resume("r1", TestClock.T0, "limit", 1));
        Trigger cron = Trigger.cron("schedule:digest.loom/MorningDigest", "0 7 * * *", ZoneId.of("Asia/Kolkata"),
                clock.instant(), DIGEST, null, null);
        assertThat(cron.nextFire()).isEqualTo(Instant.parse("2026-09-28T01:30:00Z"));
        store.upsert(cron);
        runner().tick();
        assertThat(store.get("resume:r1")).isEmpty();
        clock.set("2026-09-28T01:30:00Z");
        runner().tick();
        assertThat(fired).contains("schedule:digest.loom/MorningDigest MorningDigest@2026-09-28T01:30:00Z");
        Trigger after = store.get(cron.id()).orElseThrow();
        assertThat(after.nextFire()).isEqualTo(Instant.parse("2026-09-29T01:30:00Z"));
        assertThat(after.lastOutcome()).isEqualTo("DONE");
        assertThat(after.lastFire()).isEqualTo(Instant.parse("2026-09-28T01:30:00Z"));
        assertThat(after.attempts()).isEqualTo(1);
    }

    @Test
    void v7_5_aRunWaitingForAPersonDropsItsTrigger() {
        store.upsert(Trigger.resume("r1", TestClock.T0, "limit", 1));
        answer = (t, run) -> TriggerTarget.Outcome.human("question");
        runner().tick();
        assertThat(store.all()).isEmpty();
    }

    private Trigger sixHourly(Trigger.Misfire misfire) {
        return Trigger.every("schedule:s/Six", Duration.ofHours(6), Instant.parse("2026-09-27T06:00:00Z"), DIGEST, misfire, null)
                .fired(Instant.parse("2026-09-27T00:00:00Z"), "DONE", Instant.parse("2026-09-27T06:00:00Z"));
    }

    @Test
    void v7_8_missedSlotsFireOnceWithRunOnce() {
        store.upsert(sixHourly(Trigger.Misfire.RUN_ONCE));
        clock.set("2026-09-27T19:00:00Z");
        runner().tick();
        assertThat(fired).hasSize(1);
        assertThat(store.get("schedule:s/Six").orElseThrow().nextFire()).isEqualTo(Instant.parse("2026-09-28T00:00:00Z"));
        assertThat(records.get(0).lateMillis()).isEqualTo(Duration.ofHours(13).toMillis());
    }

    @Test
    void v7_9_missedSlotsAreSkippedWithSkip() {
        store.upsert(sixHourly(Trigger.Misfire.SKIP));
        clock.set("2026-09-27T19:00:00Z");
        runner().tick();
        assertThat(fired).isEmpty();
        Trigger t = store.get("schedule:s/Six").orElseThrow();
        assertThat(t.nextFire()).isEqualTo(Instant.parse("2026-09-28T00:00:00Z"));
        assertThat(t.lastOutcome()).startsWith("SKIPPED");
        // a slot that is merely late (not missed) still fires
        clock.set("2026-09-28T00:00:30Z");
        runner().tick();
        assertThat(fired).hasSize(1);
    }

    @Test
    void v7_10_overdueResumesAlwaysFire() {
        store.upsert(Trigger.resume("r1", Instant.parse("2026-09-25T10:00:00Z"), "limit", 1));
        runner().tick();
        assertThat(fired).containsExactly("resume:r1 r1");
    }

    @Test
    void v7_11_overlapSkipWaitsForThePausedRun() {
        Trigger cron = Trigger.cron("schedule:digest.loom/MorningDigest", "0 7 * * *", ZoneId.of("Asia/Kolkata"),
                clock.instant(), DIGEST, null, Trigger.Overlap.SKIP);
        store.upsert(cron);
        store.upsert(Trigger.resume("MorningDigest@2026-09-27T01:30:00Z", Instant.parse("2026-09-29T00:00:00Z"), "limit", 1));
        clock.set("2026-09-28T01:30:00Z");
        runner().tick();
        assertThat(fired).isEmpty();
        assertThat(records).singleElement().satisfies(r ->
                assertThat(r.outcome().status()).isEqualTo(TriggerTarget.Outcome.Status.SKIPPED_OVERLAP));
    }

    @Test
    void v7_12_overlapQueueStartsAnotherRun() {
        Trigger cron = Trigger.cron("schedule:digest.loom/MorningDigest", "0 7 * * *", ZoneId.of("Asia/Kolkata"),
                clock.instant(), DIGEST, null, Trigger.Overlap.QUEUE);
        store.upsert(cron);
        store.upsert(Trigger.resume("MorningDigest@2026-09-27T01:30:00Z", Instant.parse("2026-09-29T00:00:00Z"), "limit", 1));
        clock.set("2026-09-28T01:30:00Z");
        runner().tick();
        assertThat(fired).containsExactly("schedule:digest.loom/MorningDigest MorningDigest@2026-09-28T01:30:00Z");
    }

    @Test
    void v7_13_pauseCancelAndFireNow() {
        store.upsert(Trigger.resume("paused", TestClock.T0, "x", 1).withEnabled(false));
        store.upsert(Trigger.resume("cancelled", TestClock.T0, "x", 1));
        store.remove("resume:cancelled");
        store.upsert(Trigger.resume("later", TestClock.T0.plus(Duration.ofDays(1)), "x", 1));
        runner().tick();
        assertThat(fired).isEmpty();
        Trigger later = store.get("resume:later").orElseThrow();
        store.upsert(later.withNextFire(clock.instant())); // fire now
        runner().tick();
        assertThat(fired).containsExactly("resume:later later");
        assertThat(store.nextDue()).isEmpty(); // the paused one is not due
    }

    @Test
    void aFailingTargetIsRecordedAndTheRunnerCarriesOn() {
        store.upsert(Trigger.every("schedule:s/Boom", Duration.ofHours(1), TestClock.T0, DIGEST, null, null));
        store.upsert(Trigger.resume("r2", TestClock.T0, "x", 1));
        answer = (t, run) -> {
            if (t.id().contains("Boom")) throw new IllegalStateException("kaboom");
            return TriggerTarget.Outcome.done();
        };
        assertThat(runner().tick()).isEqualTo(2);
        assertThat(store.get("schedule:s/Boom").orElseThrow().lastOutcome()).isEqualTo("FAILED: kaboom");
        assertThat(store.get("resume:r2")).isEmpty();
    }

    @Test
    void agentTasksHaveNoRunId() {
        store.upsert(Trigger.every("schedule:s/Clean", Duration.ofHours(24), TestClock.T0,
                new Trigger.AgentTask("s", "AdminBot", "purge"), null, null));
        runner().tick();
        assertThat(fired).containsExactly("schedule:s/Clean");
        assertThat(store.get("schedule:s/Clean").orElseThrow().describe()).isEqualTo("every 24h");
    }

    @Test
    void embeddedLoopFiresOverdueOnStart() throws Exception {
        store.upsert(Trigger.resume("r1", TestClock.T0.minusSeconds(60), "x", 1));
        TriggerRunner r = runner();
        r.start(Duration.ofMillis(20));
        r.start(Duration.ofMillis(20)); // idempotent
        for (int i = 0; i < 100 && fired.isEmpty(); i++) Thread.sleep(10);
        r.stop();
        r.stop();
        assertThat(fired).containsExactly("resume:r1 r1");
    }

    @Test
    void v7_18_jitterBounds() {
        for (int i = 0; i < 1000; i++) {
            assertThat(Schedules.jitter(Duration.ofHours(1))).isBetween(Duration.ZERO, Duration.ofSeconds(30));
            assertThat(Schedules.jitter(Duration.ofSeconds(60))).isBetween(Duration.ZERO, Duration.ofSeconds(6));
        }
        assertThat(Schedules.jitter(Duration.ZERO)).isEqualTo(Duration.ZERO);
    }

    @Test
    void codecRoundTripsEveryTarget() {
        for (Trigger.Target target : List.of(new Trigger.ResumeRun("runs/a b"), DIGEST, new Trigger.AgentTask("s", "A", "t"))) {
            Trigger t = Trigger.every("schedule:x/" + target.hashCode(), Duration.ofMinutes(5), TestClock.T0, target,
                    Trigger.Misfire.SKIP, Trigger.Overlap.QUEUE).withClaim("me", TestClock.T0);
            assertThat(TriggerCodec.fromJson(TriggerCodec.toJson(t))).isEqualTo(t);
        }
        Trigger cron = Trigger.cron("c", "0 7 * * *", ZoneId.of("Asia/Kolkata"), TestClock.T0, DIGEST, null, null);
        assertThat(TriggerCodec.fromJson(TriggerCodec.toJson(cron))).isEqualTo(cron);
        assertThat(cron.describe()).isEqualTo("cron \"0 7 * * *\" Asia/Kolkata");
    }
}
