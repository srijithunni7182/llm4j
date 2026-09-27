package io.github.llm4j.loom.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.resume.TestClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Edge cases of the trigger package (coverage for N4). */
class TriggerEdgeCasesTest {

    @TempDir
    Path dir;

    @Test
    void fileClaimsThatCantBeReadCountAsHeld() throws Exception {
        FileTriggerStore store = new FileTriggerStore(dir);
        store.upsert(Trigger.resume("r1", TestClock.T0, "x", 1));
        Files.writeString(store.claimFile("resume:r1"), "half-written");
        assertThat(store.claim("resume:r1", "me", TestClock.T0.plus(Duration.ofDays(1)), Duration.ofMinutes(10))).isFalse();
        assertThat(store.get("resume:r1").orElseThrow().claimedBy()).isNull();
    }

    @Test
    void aFreshClaimIsPutBackIfATakeoverRacesIt() throws Exception {
        FileTriggerStore store = new FileTriggerStore(dir);
        store.upsert(Trigger.resume("r1", TestClock.T0, "x", 1));
        Path claim = store.claimFile("resume:r1");
        Files.writeString(claim, "other\n" + TestClock.T0);
        // someone else re-claimed just now: moving it aside finds it fresh, so it goes back
        assertThat(store.takeOverStale(claim, TestClock.T0.minusSeconds(60))).isFalse();
        assertThat(Files.readString(claim)).isEqualTo("other\n" + TestClock.T0);
        // a claim that was released meanwhile: go ahead and claim
        Files.delete(claim);
        assertThat(store.takeOverStale(claim, TestClock.T0)).isTrue();
        // a stale one is taken over
        Files.writeString(claim, "dead\n" + TestClock.T0.minusSeconds(3600));
        assertThat(store.takeOverStale(claim, TestClock.T0)).isTrue();
        assertThat(claim).doesNotExist();
        // an unreadable one is not
        Files.writeString(claim, "garbage");
        assertThat(store.takeOverStale(claim, TestClock.T0)).isFalse();
    }

    @Test
    void completeReplacesWhenClaimedAndMissingTriggersAreFine() {
        FileTriggerStore store = new FileTriggerStore(dir);
        Trigger t = Trigger.every("schedule:s/x", Duration.ofHours(1), TestClock.T0, new Trigger.ResumeRun("r"), null, null);
        store.upsert(t);
        assertThat(store.claim(t.id(), "me", TestClock.T0, Duration.ofMinutes(10))).isTrue();
        store.complete(t.id(), "me", t.fired(TestClock.T0, "DONE", TestClock.T0.plusSeconds(3600)));
        assertThat(store.get(t.id()).orElseThrow().nextFire()).isEqualTo(TestClock.T0.plusSeconds(3600));
        store.complete("missing", "me", null);
        store.remove("missing");
        assertThat(store.claim(t.id(), "me", TestClock.T0, Duration.ofMinutes(10))).isFalse(); // not due
        store.upsert(t.withEnabled(false).withNextFire(TestClock.T0));
        assertThat(store.claim(t.id(), "me", TestClock.T0, Duration.ofMinutes(10))).isFalse(); // disabled
    }

    @Test
    void everySlotsAndDescriptions() {
        Trigger every = Trigger.every("e", Duration.ofMinutes(15), TestClock.T0, new Trigger.ResumeRun("r"), null, null);
        assertThat(every.slotAfter(TestClock.T0.minusSeconds(1))).isEqualTo(TestClock.T0);
        assertThat(every.slotAfter(TestClock.T0)).isEqualTo(TestClock.T0.plus(Duration.ofMinutes(15)));
        assertThat(every.slotAfter(TestClock.T0.plus(Duration.ofMinutes(44)))).isEqualTo(TestClock.T0.plus(Duration.ofMinutes(45)));
        assertThat(every.describe()).isEqualTo("every 15m");
        Trigger at = Trigger.resume("r", TestClock.T0, "x", 1);
        assertThat(at.slotAfter(TestClock.T0)).isNull();
        assertThat(at.describe()).isEqualTo("at " + TestClock.T0);
        assertThat(at.sameRule(every)).isFalse();
        assertThatThrownBy(() -> Trigger.every("e", Duration.ZERO, TestClock.T0, new Trigger.ResumeRun("r"), null, null))
                .hasMessageContaining("positive");
        Trigger bare = new Trigger("b", Trigger.Kind.AT, null, null, new Trigger.ResumeRun("r"), null, null, null, null, 0,
                null, null, true, null, null);
        assertThat(bare.zone()).isEqualTo(java.time.ZoneOffset.UTC);
        assertThat(bare.misfire()).isEqualTo(Trigger.Misfire.RUN_ONCE);
        assertThat(bare.overlap()).isEqualTo(Trigger.Overlap.SKIP);
        assertThat(TriggerCodec.fromJson(TriggerCodec.toJson(bare))).isEqualTo(bare);
        assertThat(Trigger.cron("c", "0 7 * * *", null, TestClock.T0, new Trigger.ResumeRun("r"), null, null).zone())
                .isEqualTo(java.time.ZoneOffset.UTC);
    }

    @Test
    void codecRejectsNonsense() {
        assertThatThrownBy(() -> TriggerCodec.fromJson("{not json")).hasMessageContaining("Could not read trigger");
        assertThatThrownBy(() -> TriggerCodec.targetFromJson("{\"type\":\"teleport\"}")).hasMessageContaining("teleport");
        assertThatThrownBy(() -> TriggerCodec.targetFromJson("nope")).hasMessageContaining("Could not read trigger target");
        Map<String, Object> minimal = new java.util.HashMap<>(Map.of("id", "m", "kind", "AT",
                "target", Map.of("type", "resume", "runId", "r")));
        Trigger m = TriggerCodec.fromMap(minimal);
        assertThat(m.attempts()).isZero();
        assertThat(m.enabled()).isTrue();
    }

    @Test
    void schedulesFromClassicAndModernBlocks() {
        LoomScript s = new LoomParser(new Lexer("""
                agent A { model: "m" }
                workflow W() { delegate "x" to A -> x }
                schedule Plain { pattern: "90" agent: A task: "t" }
                schedule Days { pattern: "2d" initial_delay: "15m" agent: A task: "t" }
                schedule Once { agent: A task: "t" }
                schedule Local { cron: "0 7 * * *" run: W() }
                """).tokenize()).parseScript();
        Trigger plain = Schedules.toTrigger(s.getSchedules().get(0), "s", TestClock.T0);
        assertThat(plain.spec()).isEqualTo("PT1M30S");
        assertThat(plain.nextFire()).isEqualTo(TestClock.T0.plusSeconds(90));
        Trigger days = Schedules.toTrigger(s.getSchedules().get(1), "s", TestClock.T0);
        assertThat(days.spec()).isEqualTo("PT48H");
        assertThat(days.nextFire()).isEqualTo(TestClock.T0.plus(Duration.ofMinutes(15)));
        assertThatThrownBy(() -> Schedules.toTrigger(s.getSchedules().get(2), "s", TestClock.T0)).hasMessageContaining("needs cron or every");
        assertThat(Schedules.toTrigger(s.getSchedules().get(3), "s", TestClock.T0).zone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(Schedules.parse(" ")).isNull();
        assertThat(Schedules.parse("45")).isEqualTo(Duration.ofSeconds(45));
        assertThat(Schedules.parse("3h")).isEqualTo(Duration.ofHours(3));
        assertThat(Schedules.scheduleId("a.loom", "X")).isEqualTo("schedule:a.loom/X");
    }

    @Test
    void runnerDefaultsAndSqlDue() {
        InMemoryTriggerStore store = new InMemoryTriggerStore();
        TriggerRunner r = new TriggerRunner(store, (t, id) -> null, null);
        assertThat(r.owner()).contains("/");
        store.upsert(Trigger.resume("r", Instant.now().minusSeconds(5), "x", 1));
        assertThat(r.tick()).isEqualTo(1); // a null outcome counts as done
        assertThat(store.all()).isEmpty();
        // a listener that throws doesn't stop firing
        AtomicInteger fired = new AtomicInteger();
        store.upsert(Trigger.resume("r2", Instant.now().minusSeconds(5), "x", 1));
        new TriggerRunner(store, (t, id) -> {
            fired.incrementAndGet();
            return TriggerTarget.Outcome.done();
        }, null, "o").onFired(f -> { throw new IllegalStateException("listener"); }).tick();
        assertThat(fired.get()).isEqualTo(1);

        org.h2.jdbcx.JdbcDataSource db = new org.h2.jdbcx.JdbcDataSource();
        db.setURL("jdbc:h2:mem:edge;DB_CLOSE_DELAY=-1");
        JdbcTriggerStore.createTable(db);
        JdbcTriggerStore sql = new JdbcTriggerStore(db);
        sql.upsert(Trigger.resume("late", TestClock.T0.plusSeconds(60), "x", 1));
        sql.upsert(Trigger.resume("early", TestClock.T0, "x", 1));
        sql.upsert(Trigger.resume("future", TestClock.T0.plusSeconds(3600), "x", 1));
        assertThat(sql.due(TestClock.T0.plusSeconds(60))).extracting(Trigger::id).containsExactly("resume:early", "resume:late");
        assertThat(sql.claim("resume:early", "me", TestClock.T0, Duration.ofMinutes(10))).isTrue();
        sql.complete("resume:early", "me", Trigger.resume("early", TestClock.T0.plusSeconds(10), "y", 2));
        assertThat(sql.get("resume:early").orElseThrow().note()).isEqualTo("y");
        assertThat(TriggerTarget.Outcome.done().toString()).isEqualTo("DONE");
        assertThat(TriggerTarget.Outcome.suspended(TestClock.T0, "why").toString()).isEqualTo("SUSPENDED until " + TestClock.T0 + ": why");
        assertThat(List.of(TriggerTarget.Outcome.Status.values())).hasSize(6);
    }
}
