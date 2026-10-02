package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One contract, run against every ledger and level store (spec loom-earned-autonomy R2, R3). */
class StoreContractTest {

    @TempDir
    Path dir;

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    static Rec opened(String id, String scope, int generation, Instant at) {
        return new Rec(id + "#case#" + generation, "Refund", Rec.CASE, at, id, generation,
                Rec.map("scope", scope, "locator", "/runs/" + id, "step", "Main/s0", "fields", Map.of("amount", "20"), "level", "watch", "identity", "abc", "epoch", 1));
    }

    static Rec proposed(String id, String choice, int generation, Instant at) {
        return new Rec(id + "#proposed#" + generation, "Refund", Rec.PROPOSED, at, id, generation, Rec.map("choice", choice, "reasoning", "because", "confidence", 0.9));
    }

    static Rec decided(String id, String verdict, String decider, boolean shown, int generation, Instant at) {
        return new Rec(id + "#decided#" + generation, "Refund", Rec.DECIDED, at, id, generation, Rec.map("verdict", verdict, "decider", decider, "shown", shown, "millis", 1500));
    }

    @Test
    @Tag("EA-V2.2")
    void aLedgerIsAppendOnlyIdempotentAndKeepsOrder() {
        Stores.each(dir, s -> {
            Rec a = opened("c1", "gold", 1, T0);
            s.ledger.append(a);
            s.ledger.append(proposed("c1", "approve", 1, T0.plusSeconds(1)));
            s.ledger.append(a); // the same fact again
            s.ledger.append(decided("c1", "approve", "ada", false, 1, T0.plusSeconds(2)));
            s.ledger.append(opened("c2", "basic", 1, T0.plusSeconds(3)));
            s.ledger.append(decided("c1", "approve", "ada", false, 1, T0.plusSeconds(9))); // the same id again

            assertThat(s.ledger.records("Refund")).as(s.kind).extracting(Rec::id).containsExactly(
                    "c1#case#1", "c1#proposed#1", "c1#decided#1", "c2#case#1");
            assertThat(s.ledger.records("Other")).isEmpty();

            Case c1 = s.ledger.get("Refund", "c1").orElseThrow();
            assertThat(c1.scope()).isEqualTo("gold");
            assertThat(c1.locator()).isEqualTo("/runs/c1");
            assertThat(c1.fields()).containsEntry("amount", "20");
            assertThat(c1.proposal()).isEqualTo("approve");
            assertThat(c1.confidence()).isEqualTo(0.9);
            assertThat(c1.verdict()).isEqualTo("approve");
            assertThat(c1.decider()).isEqualTo("ada");
            assertThat(c1.shown()).isFalse();
            assertThat(c1.millis()).isEqualTo(1500);
            assertThat(c1.blindEvidence()).isTrue();
            assertThat(s.ledger.cases("Refund", c -> c.scope().equals("basic"))).extracting(Case::id).containsExactly("c2");
            assertThat(s.ledger.get("Refund", "nobody")).isEmpty();
        });
    }

    @Test
    @Tag("EA-V2.2")
    void aRecordAboutAnUnknownCaseIsFoldedAsAnOrphanNotDropped() {
        Stores.each(dir, s -> {
            s.ledger.append(decided("ghost", "reject", "ada", false, 1, T0));
            assertThat(s.ledger.cases("Refund")).extracting(Case::id).containsExactly("ghost");
            assertThat(s.ledger.cases("Refund").get(0).verdict()).isEqualTo("reject");
            assertThat(s.ledger.cases("Refund").get(0).scope()).isEmpty();
        });
    }

    @Test
    @Tag("EA-V2.9")
    void aCaseDecidedAgainInALaterGenerationSupersedesTheEarlierOne() {
        Stores.each(dir, s -> {
            s.ledger.append(opened("c1", "gold", 1, T0));
            s.ledger.append(decided("c1", "reject", "ada", false, 1, T0.plusSeconds(1)));
            s.ledger.append(opened("c1", "gold", 2, T0.plusSeconds(5)));
            s.ledger.append(decided("c1", "approve", "ada", false, 2, T0.plusSeconds(6)));

            List<Case> all = s.ledger.cases("Refund");
            assertThat(all).hasSize(2);
            assertThat(all.get(0).superseded()).isTrue();
            assertThat(all.get(1).superseded()).isFalse();
            assertThat(Cases.current(all)).extracting(Case::verdict).containsExactly("approve");
            assertThat(s.ledger.get("Refund", "c1").orElseThrow().generation()).isEqualTo(2);
            assertThat(all.get(0).blindEvidence()).isFalse();
        });
    }

    @Test
    @Tag("EA-V5.5")
    void anOutcomeIsALaterRecordThatTheCaseFoldsIn() {
        Stores.each(dir, s -> {
            s.ledger.append(opened("c1", "gold", 1, T0));
            s.ledger.append(decided("c1", "approve", "agent", false, 1, T0.plusSeconds(1)));
            s.ledger.append(new Rec("c1#outcome#reversed#chargeback", "Refund", Rec.OUTCOME, T0.plusSeconds(100), "c1", 1, Rec.map("result", "reversed", "note", "chargeback")));
            Case c = s.ledger.get("Refund", "c1").orElseThrow();
            assertThat(c.reversed()).isTrue();
            assertThat(c.decidedByAgent()).isTrue();
            assertThat(c.blindEvidence()).isFalse();
        });
    }

    @Test
    @Tag("EA-V2.4")
    void concurrentAppendsToTheSameLedgerLoseNothingAndTearNothing() throws Exception {
        for (Stores s : List.of(Stores.memory(), Stores.file(Files.createTempDirectory(dir, "c")), Stores.jdbc())) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch go = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int base = t * 25;
                futures.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 25; i++) s.ledger.append(opened("c" + (base + i), "gold", 1, T0.plusSeconds(base + i)));
                    return null;
                }));
            }
            go.countDown();
            for (var f : futures) f.get();
            pool.shutdown();
            assertThat(s.ledger.records("Refund")).as(s.kind).hasSize(200);
            assertThat(s.ledger.unreadable("Refund")).isZero();
        }
    }

    @Test
    @Tag("EA-V2.4")
    void aTornLastLineIsIgnoredOnReadReportedAndRepairedByTheNextAppend() throws Exception {
        FileLedger ledger = new FileLedger(dir);
        ledger.append(opened("c1", "gold", 1, T0));
        ledger.append(opened("c2", "gold", 1, T0.plusSeconds(1)));
        Path file = dir.resolve("Refund").resolve("ledger.jsonl");
        Files.write(file, "{\"id\":\"c3#case#1\",\"decision\":\"Refund\",\"kind\":\"ca".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);

        assertThat(ledger.records("Refund")).hasSize(2);
        assertThat(ledger.unreadable("Refund")).isEqualTo(1);

        FileLedger another = new FileLedger(dir);
        another.append(opened("c3", "gold", 1, T0.plusSeconds(2)));
        assertThat(another.records("Refund")).extracting(Rec::caseId).containsExactly("c1", "c2", "c3");
        assertThat(another.unreadable("Refund")).isZero();
        assertThat(Files.readString(file)).endsWith("\n");
    }

    @Test
    @Tag("EA-V2.4")
    void aFileLedgerSeesWhatAnotherProcessWroteAndDoesNotWriteItTwice() {
        FileLedger one = new FileLedger(dir);
        FileLedger two = new FileLedger(dir);
        one.append(opened("c1", "gold", 1, T0));
        two.append(opened("c1", "gold", 1, T0)); // a resumed run in another process
        two.append(opened("c2", "gold", 1, T0));
        assertThat(one.records("Refund")).extracting(Rec::id).containsExactly("c1#case#1", "c2#case#1");
    }

    @Test
    @Tag("EA-V2.7")
    void retentionRemovesTheFieldsAndReasoningOfOldCasesAndKeepsTheCounts() {
        Stores.each(dir, s -> {
            s.ledger.append(opened("old", "gold", 1, T0));
            s.ledger.append(proposed("old", "approve", 1, T0));
            s.ledger.append(decided("old", "approve", "ada", false, 1, T0));
            s.ledger.append(opened("new", "gold", 1, T0.plusSeconds(1000)));
            s.ledger.purgeFields("Refund", T0.plusSeconds(500));

            Case old = s.ledger.get("Refund", "old").orElseThrow();
            assertThat(old.fields()).as(s.kind).isEmpty();
            assertThat(old.reasoning()).isNull();
            assertThat(old.verdict()).isEqualTo("approve");
            assertThat(old.proposal()).isEqualTo("approve");
            assertThat(s.ledger.get("Refund", "new").orElseThrow().fields()).containsEntry("amount", "20");
            assertThat(s.ledger.records("Refund")).hasSize(4);
        });
    }

    @Test
    @Tag("EA-V8.1")
    void aDecisionNameThatCouldEscapeTheStoreIsRefused() {
        FileLedger ledger = new FileLedger(dir);
        assertThatThrownBy(() -> ledger.append(new Rec("x", "../../etc", Rec.CASE, T0, "c", 1, Map.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileLevelStore(dir).get("a/b", "s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcLevelStore(null).get("a b", "s")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("EA-V3.12")
    void aLevelMovesOnlyWhenTheCallerSawTheCurrentStateAndSurvivesReopening() {
        Stores.each(dir, s -> {
            LevelState first = new LevelState(Level.WATCH, 1, "id1", false, T0, "first case", 1);
            assertThat(s.levels.get("Refund", "gold")).isEmpty();
            assertThat(s.levels.compareAndSet("Refund", "gold", null, first)).as(s.kind).isTrue();
            assertThat(s.levels.compareAndSet("Refund", "gold", null, first)).as("already there").isFalse();

            LevelState up = first.withLevel(Level.SUGGEST, false, T0.plusSeconds(1), "earned");
            assertThat(s.levels.compareAndSet("Refund", "gold", first, up)).isTrue();
            assertThat(s.levels.compareAndSet("Refund", "gold", first, first.withLevel(Level.ACT, true, T0, "stale"))).as("the loser re-reads").isFalse();
            assertThat(s.levels.get("Refund", "gold")).contains(up);
            assertThat(up.version()).isEqualTo(2);

            assertThat(s.levels.compareAndSet("Refund", "basic", null, new LevelState(Level.WATCH, 1, "id1", false, T0, "first case", 1))).isTrue();
            assertThat(s.levels.scopes("Refund")).containsOnlyKeys("gold", "basic");
            assertThat(s.levels.get("Refund", "gold").orElseThrow().level()).isEqualTo(Level.SUGGEST);
            assertThat(s.levels.scopes("Other")).isEmpty();
        });
    }

    @Test
    @Tag("EA-V3.12")
    void ofManyThreadsMovingTheSameLevelExactlyOneWins() throws Exception {
        for (Stores s : List.of(Stores.memory(), Stores.file(Files.createTempDirectory(dir, "l")), Stores.jdbc())) {
            LevelState first = new LevelState(Level.WATCH, 1, "id1", false, T0, "start", 1);
            s.levels.compareAndSet("Refund", "gold", null, first);
            ExecutorService pool = Executors.newFixedThreadPool(6);
            AtomicInteger wins = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    if (s.levels.compareAndSet("Refund", "gold", first, first.withLevel(Level.SUGGEST, false, T0, "race"))) wins.incrementAndGet();
                    return null;
                }));
            }
            go.countDown();
            for (var f : futures) f.get();
            pool.shutdown();
            assertThat(wins.get()).as(s.kind).isEqualTo(1);
        }
    }

    @Test
    @Tag("EA-V3.11")
    void aFreezeCoversOneDecisionOrAllAndCanBeLifted() {
        Stores.each(dir, s -> {
            assertThat(s.levels.frozen("Refund")).isFalse();
            s.levels.setFreeze("Refund", new LevelStore.Freeze("incident", T0));
            assertThat(s.levels.frozen("Refund")).as(s.kind).isTrue();
            assertThat(s.levels.frozen("Other")).isFalse();
            assertThat(s.levels.freeze("Refund").orElseThrow().reason()).isEqualTo("incident");
            s.levels.clearFreeze("Refund");
            assertThat(s.levels.frozen("Refund")).isFalse();
            s.levels.setFreeze("*", new LevelStore.Freeze("everything", T0));
            assertThat(s.levels.frozen("Refund")).isTrue();
            assertThat(s.levels.frozen("Other")).isTrue();
            s.levels.clearFreeze("*");
            assertThat(s.levels.frozen("Other")).isFalse();
            assertThat(s.levels.scopes("Refund")).isEmpty();
        });
    }
}
