package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification C8: a hand-edited ledger that forges a promotion is found. */
@Tag("L9")
class SealTest {

    static final Instant T = Instant.parse("2026-03-01T00:00:00Z");

    @TempDir Path dir;

    private static Rec level(String scope, int version, String from, String to) {
        return new Rec(
                "D#level#" + scope + "#" + version,
                "D",
                Rec.LEVEL,
                T.plusSeconds(version),
                "",
                1,
                Rec.map(
                        "scope",
                        scope,
                        "from",
                        from,
                        "to",
                        to,
                        "kind",
                        "promoted",
                        "reason",
                        "earned",
                        "by",
                        "runtime",
                        "epoch",
                        1,
                        "identity",
                        "a",
                        "version",
                        version));
    }

    private static void write(Ledger l, Rec r) {
        Seal.append(l, r);
    }

    private Path file() {
        return dir.resolve("D/ledger.jsonl");
    }

    /** A file ledger with a legacy record, then three sealed ones. */
    private FileLedger sealed() {
        FileLedger ledger = new FileLedger(dir);
        ledger.append(level("gold", 1, null, "watch")); // written by the previous release: no seal
        write(ledger, level("gold", 2, "watch", "suggest"));
        write(ledger, level("gold", 3, "suggest", "act"));
        write(
                ledger,
                new Rec(
                        "D#freeze#1",
                        "D",
                        Rec.FROZEN,
                        T.plusSeconds(10),
                        "",
                        1,
                        Rec.map("frozen", true, "reason", "incident", "scope", "D")));
        return ledger;
    }

    private void edit(String from, String to) throws Exception {
        String text = Files.readString(file());
        assertThat(text).contains(from);
        Files.writeString(file(), text.replace(from, to));
    }

    @Test
    void aLedgerThePreviousReleaseWroteVerifiesAsNotYetSealedAndIsNotMarkedBad() {
        FileLedger ledger = new FileLedger(dir);
        ledger.append(level("gold", 1, null, "watch"));
        ledger.append(level("gold", 2, "watch", "suggest"));
        Seal.Result r = Seal.verify(ledger, "D");
        assertThat(r.ok()).isTrue();
        assertThat(r.sealed()).isFalse();
    }

    @Test
    void theFirstRecordTheRuntimeWritesAnchorsWhatWasThereAndSealsItself() {
        FileLedger ledger = sealed();
        assertThat(ledger.recordsOfKind("D", Rec.SEAL_BASE)).hasSize(1);
        assertThat(ledger.recordsOfKind("D", Rec.LEVEL).get(0).str("seal"))
                .as("the legacy record stays as it was")
                .isNull();
        assertThat(ledger.recordsOfKind("D", Rec.LEVEL).get(1).str("seal")).isNotBlank();
        Seal.Result r = Seal.verify(ledger, "D");
        assertThat(r.ok()).isTrue();
        assertThat(r.sealed()).isTrue();
        assertThat(new FileLedger(dir).sealKey())
                .as("the key is kept with the store")
                .isEqualTo(ledger.sealKey());
        assertThat(Files.exists(dir.resolve(".seal-key"))).isTrue();
    }

    @Test
    void aRecordThatIsNotOfTheseKindsIsNotSealed() {
        FileLedger ledger = new FileLedger(dir);
        write(ledger, new Rec("c1", "D", Rec.CASE, T, "c1", 1, Rec.map("scope", "gold")));
        assertThat(ledger.records("D").get(0).str("seal")).isNull();
        assertThat(ledger.recordsOfKind("D", Rec.SEAL_BASE)).isEmpty();
    }

    @Test
    void aForgedPromotionIsFoundWhicheverWayItIsDone() throws Exception {
        // 1. the level of a record is changed in the file
        sealed();
        edit("\"to\":\"suggest\"", "\"to\":\"act\"");
        assertThat(Seal.verify(new FileLedger(dir), "D"))
                .satisfies(
                        r -> {
                            assertThat(r.ok()).isFalse();
                            assertThat(r.why()).contains("changed");
                        });
    }

    @Test
    void aRecordAddedByHandAtTheEndIsFound() throws Exception {
        sealed();
        Files.writeString(
                file(),
                Files.readString(file())
                        + "{\"id\":\"D#level#gold#4\",\"decision\":\"D\",\"kind\":\"level\",\"at\":\"2026-03-02T00:00:00Z\",\"caseId\":\"\",\"generation\":1,"
                        + "\"body\":{\"scope\":\"gold\",\"to\":\"act\",\"version\":4,\"epoch\":1,\"identity\":\"a\",\"kind\":\"promoted\",\"reason\":\"trust me\",\"by\":\"runtime\"}}\n");
        Seal.Result r = Seal.verify(new FileLedger(dir), "D");
        assertThat(r.ok()).isFalse();
        assertThat(r.why()).contains("no seal");
        assertThat(r.record()).isEqualTo("D#level#gold#4");
    }

    @Test
    void aRecordSlippedInAmongTheOnesThePreviousReleaseWroteIsFound() throws Exception {
        FileLedger ledger = new FileLedger(dir);
        ledger.append(level("gold", 1, null, "watch"));
        ledger.append(level("gold", 2, "watch", "suggest"));
        write(ledger, level("gold", 3, "suggest", "act")); // anchors the two above
        String text = Files.readString(file());
        String forged =
                "{\"id\":\"D#level#basic#1\",\"decision\":\"D\",\"kind\":\"level\",\"at\":\"2026-03-01T00:00:05Z\",\"caseId\":\"\",\"generation\":1,\"body\":{\"scope\":\"basic\","
                        + "\"to\":\"act\",\"version\":1,\"epoch\":1,\"identity\":\"a\",\"kind\":\"first\",\"reason\":\"first case of this scope\",\"by\":\"runtime\"}}\n";
        int firstLine = text.indexOf('\n') + 1;
        Files.writeString(
                file(), text.substring(0, firstLine) + forged + text.substring(firstLine));
        Seal.Result r = Seal.verify(new FileLedger(dir), "D");
        assertThat(r.ok()).isFalse();
        assertThat(r.why()).contains("anchor");
    }

    @Test
    void aRecordRemovedIsFound() throws Exception {
        sealed();
        List<String> lines = new ArrayList<>(Files.readAllLines(file()));
        lines.removeIf(l -> l.contains("D#level#gold#2"));
        Files.write(file(), lines);
        assertThat(Seal.verify(new FileLedger(dir), "D").ok()).isFalse();
    }

    @Test
    void aRecordOfOneScopeGivenTheSealOfAnotherIsFoundBecauseTheSealCoversWhatItSays()
            throws Exception {
        sealed();
        String text = Files.readString(file());
        // copy the seal of version 2 onto version 3's record and change its target
        String seal2 = text.replaceAll("(?s).*D#level#gold#2.*?\"seal\":\"([0-9a-f]+)\".*", "$1");
        assertThat(seal2).hasSize(64);
        String line3 =
                Files.readAllLines(file()).stream()
                        .filter(l -> l.contains("D#level#gold#3"))
                        .findFirst()
                        .orElseThrow();
        String swapped = line3.replaceAll("\"seal\":\"[0-9a-f]+\"", "\"seal\":\"" + seal2 + "\"");
        Files.writeString(file(), text.replace(line3, swapped));
        assertThat(Seal.verify(new FileLedger(dir), "D").ok()).isFalse();
    }

    @Test
    void aDifferentKeyMeansNothingVerifies() throws Exception {
        sealed();
        Files.writeString(
                dir.resolve(".seal-key"),
                java.util.Base64.getEncoder().encodeToString(Seal.newKey()));
        Seal.Result r = Seal.verify(new FileLedger(dir), "D");
        assertThat(r.ok()).isFalse();
        assertThat(r.why()).contains("key");
    }

    @Test
    void anOperatorWhoHasLookedCanAcceptTheRecordAndItIsSealedFromThere() throws Exception {
        sealed();
        edit("\"to\":\"suggest\"", "\"to\":\"act\"");
        FileLedger again = new FileLedger(dir);
        assertThat(Seal.verify(again, "D").ok()).isFalse();
        Seal.accept(
                again, "D", T.plusSeconds(100), "operator:ada", "the edit was mine, to fix a typo");
        assertThat(Seal.verify(again, "D").ok()).isTrue();
        Rec accepted = again.recordsOfKind("D", Rec.SEAL_BASE).get(1);
        assertThat(accepted.str("by")).isEqualTo("operator:ada");
        assertThat(accepted.str("reason")).contains("typo");
        write(again, level("gold", 4, "act", "suggest"));
        assertThat(Seal.verify(again, "D").ok()).isTrue();
        // and a later forgery is found again
        edit(
                "\"to\":\"suggest\"",
                "\"to\":\"act\""); // the only one left is the record written after the acceptance
        assertThat(Seal.verify(new FileLedger(dir), "D").ok()).isFalse();
    }

    @Test
    void everyKindOfLedgerSealsAndVerifiesAndFindsAnUnsealedRecord() {
        Stores.each(
                dir,
                stores -> {
                    Ledger ledger = stores.ledger;
                    write(ledger, level("gold", 1, null, "watch"));
                    write(ledger, level("gold", 2, "watch", "suggest"));
                    assertThat(Seal.verify(ledger, "D").ok()).as(stores.kind).isTrue();
                    assertThat(ledger.sealKey())
                            .as(stores.kind + " keeps one key")
                            .isEqualTo(ledger.sealKey());
                    ledger.append(level("gold", 3, "suggest", "act"));
                    assertThat(Seal.verify(ledger, "D").ok())
                            .as(stores.kind + " after an unsealed append")
                            .isFalse();
                });
    }

    @Test
    void manyWritersAtOnceLeaveARecordThatVerifies() throws Exception {
        FileLedger ledger = new FileLedger(dir);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<?>> done = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int id = t;
            done.add(
                    pool.submit(
                            () -> {
                                for (int i = 0; i < 25; i++) {
                                    write(
                                            ledger,
                                            new Rec(
                                                    "D#freeze#" + id + "#" + i,
                                                    "D",
                                                    Rec.FROZEN,
                                                    T.plusSeconds(id * 100 + i),
                                                    "",
                                                    1,
                                                    Rec.map(
                                                            "frozen",
                                                            i % 2 == 0,
                                                            "reason",
                                                            "w" + id,
                                                            "scope",
                                                            "D")));
                                    write(
                                            ledger,
                                            new Rec(
                                                    "D#promotion#s" + id + "#" + i,
                                                    "D",
                                                    Rec.PROMOTION_PROPOSED,
                                                    T.plusSeconds(id * 100 + i),
                                                    "",
                                                    1,
                                                    Rec.map(
                                                            "scope", "s" + id, "to", "act", "cases",
                                                            i)));
                                }
                            }));
        }
        for (var f : done) f.get();
        pool.shutdown();
        Seal.Result r = Seal.verify(new FileLedger(dir), "D");
        assertThat(r.ok()).as(r.why()).isTrue();
        assertThat(r.records()).isGreaterThan(400);
        assertThat(new FileLedger(dir).recordsOfKind("D", Rec.SEAL_BASE))
                .as("one anchor, however many raced to write it")
                .hasSize(1);
    }

    @Test
    void aMemoryLedgerIsSealedToo() {
        MemoryLedger ledger = new MemoryLedger();
        write(ledger, level("gold", 1, null, "watch"));
        write(ledger, level("gold", 2, "watch", "suggest"));
        assertThat(Seal.verify(ledger, "D").ok()).isTrue();
        ledger.append(level("gold", 3, "suggest", "act")); // not through the runtime: no seal
        Seal.Result r = Seal.verify(ledger, "D");
        assertThat(r.ok()).isFalse();
        assertThat(r.record()).isEqualTo("D#level#gold#3");
    }

    @Test
    void sealingGivesTheSameAnswerWhateverTheNumbersAreStoredAs() throws Exception {
        FileLedger ledger = new FileLedger(dir);
        write(
                ledger,
                new Rec(
                        "D#level#gold#1",
                        "D",
                        Rec.LEVEL,
                        T,
                        "",
                        1,
                        Rec.map(
                                "scope",
                                "gold",
                                "to",
                                "watch",
                                "version",
                                1,
                                "epoch",
                                1L,
                                "identity",
                                "a",
                                "ratio",
                                0.5,
                                "big",
                                5_000_000_000L,
                                "forced",
                                true,
                                "list",
                                List.of("a", 1),
                                "nested",
                                Map.of("k", 2))));
        assertThat(Seal.verify(new FileLedger(dir), "D").ok())
                .as("read back from the file")
                .isTrue();
    }
}
