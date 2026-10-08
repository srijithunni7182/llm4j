package io.github.llm4j.loom.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.resume.TestClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V7.14–V7.17: claims, races and restarts on the file and SQL stores. */
class StoreContractTest {

    @TempDir
    Path dir;

    final TestClock clock = new TestClock();
    static final AtomicInteger DB = new AtomicInteger();

    private JdbcTriggerStore sql() {
        org.h2.jdbcx.JdbcDataSource db = new org.h2.jdbcx.JdbcDataSource();
        db.setURL("jdbc:h2:mem:triggers" + DB.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        JdbcTriggerStore.createTable(db);
        return new JdbcTriggerStore(db);
    }

    /** A file store whose ticks don't serialise, so claims alone must keep firings exclusive. */
    private FileTriggerStore fileWithoutTickLock() {
        return new FileTriggerStore(dir) {
            @Override
            public AutoCloseable tickLock() {
                return () -> { };
            }
        };
    }

    private void basicContract(TriggerStore store) {
        Trigger t = Trigger.resume("runs/one two", TestClock.T0, "limit", 1);
        store.upsert(t);
        assertThat(store.get(t.id())).contains(t);
        assertThat(store.claim(t.id(), "a", TestClock.T0.minusSeconds(1), Duration.ofMinutes(10))).isFalse(); // not due
        assertThat(store.claim(t.id(), "a", TestClock.T0, Duration.ofMinutes(10))).isTrue();
        assertThat(store.get(t.id()).orElseThrow().claimedBy()).isEqualTo("a");
        assertThat(store.claim(t.id(), "b", TestClock.T0, Duration.ofMinutes(10))).isFalse();
        store.complete(t.id(), "b", null); // not b's: ignored
        assertThat(store.get(t.id())).isPresent();
        store.upsert(t.withNextFire(TestClock.T0.plusSeconds(60))); // rewritten while claimed: claim cleared
        assertThat(store.get(t.id()).orElseThrow().claimedBy()).isNull();
        store.complete(t.id(), "a", null); // a's claim is gone: the rewrite stands
        assertThat(store.get(t.id()).orElseThrow().nextFire()).isEqualTo(TestClock.T0.plusSeconds(60));
        assertThat(store.claim(t.id(), "a", TestClock.T0.plusSeconds(60), Duration.ofMinutes(10))).isTrue();
        store.complete(t.id(), "a", t.fired(TestClock.T0, "DONE", TestClock.T0.plusSeconds(120)));
        assertThat(store.get(t.id()).orElseThrow().attempts()).isEqualTo(2);
        assertThat(store.nextDue()).contains(TestClock.T0.plusSeconds(120));
        store.upsert(t.withEnabled(false));
        assertThat(store.claim(t.id(), "a", TestClock.T0.plus(Duration.ofDays(1)), Duration.ofMinutes(10))).isFalse();
        assertThat(store.due(TestClock.T0.plus(Duration.ofDays(1)))).isEmpty();
        store.remove(t.id());
        assertThat(store.get(t.id())).isEmpty();
        assertThat(store.all()).isEmpty();
        assertThat(store.claim("missing", "a", TestClock.T0, Duration.ofMinutes(10))).isFalse();
    }

    @Test
    void contractInMemory() {
        basicContract(new InMemoryTriggerStore());
    }

    @Test
    void contractFile() {
        basicContract(new FileTriggerStore(dir));
    }

    @Test
    void contractSql() {
        basicContract(sql());
    }

    private void staleClaimIsTakenOver(TriggerStore store) {
        store.upsert(Trigger.resume("r1", TestClock.T0, "x", 1));
        assertThat(store.claim("resume:r1", "dead", TestClock.T0, Duration.ofMinutes(10))).isTrue(); // then it crashed
        AtomicInteger fired = new AtomicInteger();
        TriggerRunner other = new TriggerRunner(store, (t, r) -> {
            fired.incrementAndGet();
            return TriggerTarget.Outcome.done();
        }, clock, "alive").staleAfter(Duration.ofMinutes(10));
        clock.set(TestClock.T0.plus(Duration.ofMinutes(5)));
        assertThat(other.tick()).isZero(); // still fresh
        clock.set(TestClock.T0.plus(Duration.ofMinutes(11)));
        assertThat(other.tick()).isEqualTo(1);
        assertThat(fired.get()).isEqualTo(1);
        assertThat(store.all()).isEmpty();
    }

    @Test
    void v7_14_staleClaimFile() {
        staleClaimIsTakenOver(new FileTriggerStore(dir));
    }

    @Test
    void v7_14b_staleClaimSql() {
        staleClaimIsTakenOver(sql());
    }

    private void everyTriggerFiresOnce(List<TriggerStore> stores, int triggers, int threads) throws Exception {
        for (int i = 0; i < triggers; i++) stores.get(0).upsert(Trigger.resume("r" + i, TestClock.T0, "x", 1));
        Map<String, AtomicInteger> count = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int n = 0; n < threads; n++) {
            TriggerStore store = stores.get(n % stores.size());
            String owner = "runner-" + n;
            pool.submit(() -> {
                go.await();
                new TriggerRunner(store, (t, r) -> {
                    count.computeIfAbsent(t.id(), k -> new AtomicInteger()).incrementAndGet();
                    Thread.sleep(2);
                    return TriggerTarget.Outcome.done();
                }, clock, owner).tick();
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(count).hasSize(triggers);
        assertThat(count.values()).allSatisfy(c -> assertThat(c.get()).isEqualTo(1));
        assertThat(stores.get(0).all()).isEmpty();
    }

    @Test
    void v7_15_fileStoreRacingRunnersFireEachOnce() throws Exception {
        everyTriggerFiresOnce(List.of(fileWithoutTickLock(), fileWithoutTickLock()), 20, 6);
    }

    @Test
    void v7_15b_tickLockLetsOnlyOneTickRun() throws Exception {
        FileTriggerStore store = new FileTriggerStore(dir);
        AutoCloseable held = store.tickLock();
        assertThat(held).isNotNull();
        assertThat(store.tickLock()).isNull(); // a second tick in this process
        store.upsert(Trigger.resume("r1", TestClock.T0, "x", 1));
        assertThat(new TriggerRunner(store, (t, r) -> TriggerTarget.Outcome.done(), clock, "x").tick()).isZero();
        held.close();
        assertThat(new TriggerRunner(store, (t, r) -> TriggerTarget.Outcome.done(), clock, "x").tick()).isEqualTo(1);
    }

    @Test
    void v7_16_sqlRacingRunnersFireEachOnce() throws Exception {
        JdbcTriggerStore store = sql();
        everyTriggerFiresOnce(List.of(store), 50, 4);
    }

    @Test
    void v7_17_restartKeepsPendingTriggers() {
        new FileTriggerStore(dir).upsert(Trigger.resume("r1", TestClock.T0.plusSeconds(30), "x", 1));
        FileTriggerStore restarted = new FileTriggerStore(dir);
        assertThat(restarted.nextDue()).contains(TestClock.T0.plusSeconds(30));
        clock.set(TestClock.T0.plusSeconds(30));
        AtomicInteger fired = new AtomicInteger();
        new TriggerRunner(restarted, (t, r) -> {
            fired.incrementAndGet();
            return TriggerTarget.Outcome.done();
        }, clock, "new").tick();
        assertThat(fired.get()).isEqualTo(1);

        org.h2.jdbcx.JdbcDataSource db = new org.h2.jdbcx.JdbcDataSource();
        db.setURL("jdbc:h2:mem:restart;DB_CLOSE_DELAY=-1");
        JdbcTriggerStore.createTable(db);
        new JdbcTriggerStore(db).upsert(Trigger.resume("r2", TestClock.T0, "x", 1));
        assertThat(new JdbcTriggerStore(db).get("resume:r2")).isPresent();
    }

    @Test
    void fileStoreIgnoresStrayFiles() throws Exception {
        FileTriggerStore store = new FileTriggerStore(dir);
        store.upsert(Trigger.resume("r1", TestClock.T0, "x", 1));
        Files.writeString(dir.resolve("triggers/.write-123.tmp"), "{");
        Files.writeString(dir.resolve("triggers/junk.claim"), "garbage-without-newline");
        assertThat(store.all()).hasSize(1);
        assertThat(store.dir()).isEqualTo(dir);
    }

    /** Delegates to a store, but "crashes" (throws) on the n-th call. */
    static final class CrashingStore implements TriggerStore {
        final TriggerStore inner;
        final int crashAt;
        int calls;
        boolean completed;

        CrashingStore(TriggerStore inner, int crashAt) {
            this.inner = inner;
            this.crashAt = crashAt;
        }

        private void step() {
            if (++calls == crashAt) throw new IllegalStateException("crash at store call " + crashAt);
        }

        @Override public void upsert(Trigger t) { step(); inner.upsert(t); }
        @Override public java.util.Optional<Trigger> get(String id) { step(); return inner.get(id); }
        @Override public List<Trigger> all() { step(); return inner.all(); }
        @Override public void remove(String id) { step(); inner.remove(id); }
        @Override public List<Trigger> due(Instant now) { step(); return inner.due(now); }
        @Override public boolean claim(String id, String o, Instant now, Duration stale) { step(); return inner.claim(id, o, now, stale); }
        @Override public void complete(String id, String o, Trigger next) {
            step();
            inner.complete(id, o, next);
            completed = true;
        }
    }

    @Test
    void n1b_aCrashAtAnyStoreCallLosesNothingAndNeverRefiresACompletedTrigger() {
        for (int crashAt = 1; crashAt <= 6; crashAt++) {
            TestClock c = new TestClock();
            InMemoryTriggerStore store = new InMemoryTriggerStore();
            store.upsert(Trigger.resume("r1", TestClock.T0, "x", 1));
            AtomicInteger fired = new AtomicInteger();
            TriggerTarget target = (t, r) -> {
                fired.incrementAndGet();
                return TriggerTarget.Outcome.done();
            };
            CrashingStore crashing = new CrashingStore(store, crashAt);
            try {
                new TriggerRunner(crashing, target, c, "first").tick();
            } catch (IllegalStateException crash) {
                // the process died here
            }
            c.advance(Duration.ofMinutes(11)); // its claim is stale by now
            new TriggerRunner(store, target, c, "second").tick();
            assertThat(store.all()).as("crash at call %d", crashAt).isEmpty(); // nothing left behind
            assertThat(fired.get()).as("crash at call %d", crashAt).isBetween(1, crashing.completed ? 1 : 2);
        }
    }
}
