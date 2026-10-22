package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.autonomy.expr.ExprParser;
import io.github.llm4j.loom.autonomy.expr.Interpreter;
import io.github.llm4j.loom.autonomy.expr.V;
import io.github.llm4j.loom.ladder.Blobs;
import io.github.llm4j.loom.ladder.HistoryBuilder;
import io.github.llm4j.loom.ladder.LCase;
import io.github.llm4j.loom.ladder.LedgerFold;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verification J2: artifacts of 100 KB, 1 MB and 5 MB as proposals and edits. Past a limit the text
 * is kept once, apart from the record of the case, which says where it is; an edit's size is
 * computed within a bound; and retention removes the text from the one place it is.
 */
@Tag("L9")
class ArtifactReferenceTest {

    @TempDir Path dir;

    static String document(int size) {
        StringBuilder b = new StringBuilder(size + 16);
        for (int i = 0; b.length() < size; i++) b.append("word").append(i).append(' ');
        return b.substring(0, size);
    }

    @ParameterizedTest
    @ValueSource(ints = {100_000, 1_000_000, 5_000_000})
    void aLargeProposalAndItsEditAreKeptByReferenceAndMeasuredWithinABoundAndRetentionRemovesThem(
            int size) {
        String proposed = document(size);
        String edited = proposed + " (and one more sentence)";
        MemoryLedger ledger = new MemoryLedger();
        DecideHarness h =
                new DecideHarness(dir, ledger, new MemoryLevelStore(), LadderBoundRunTest.PUBLISH);
        h.rawAgent = f -> "{\"post\": \"" + proposed + "\", \"reasoning\": \"draft\"}";
        h.person = f -> "accept";
        h.runCase("p1", h.inputs("x", 1));
        h.person = f -> edited;
        h.runCase("p2", h.inputs("x", 1));

        // the records of the cases hold no artifact, only a reference, its size and how it begins
        for (Rec r : ledger.records("Publish")) {
            if (Rec.PROPOSED.equals(r.kind()) || Rec.RESPONDED.equals(r.kind())) {
                assertThat(r.body().toString().length())
                        .as(r.kind() + " " + r.id())
                        .isLessThan(2_000);
                assertThat(r.body()).doesNotContainKeys("value");
            }
        }
        List<Rec> blobs = ledger.recordsOfKind("Publish", Rec.BLOB);
        assertThat(blobs)
                .as(
                        "the proposal once (the accepted case's final text is the same text), and the edit once")
                .hasSize(2);
        Rec firstProposal = ledger.recordsOfKind("Publish", Rec.PROPOSED).get(0);
        assertThat(firstProposal.str("valueRef")).isNotBlank();
        assertThat(firstProposal.num("valueSize").intValue()).isEqualTo(size);
        assertThat(firstProposal.str("valueHead")).isEqualTo(proposed.substring(0, 160));
        assertThat(ledger.recordsOfKind("Publish", Rec.RESPONDED).get(0).str("finalRef"))
                .as("an accept's final text is the proposal: the same copy")
                .isEqualTo(firstProposal.str("valueRef"));

        // read back whole
        List<LCase> cases = LedgerFold.cases(ledger.records("Publish"));
        assertThat(cases.get(0).proposalValue()).hasSize(size).isEqualTo(proposed);
        assertThat(cases.get(1).responseFinal()).isEqualTo(edited);

        // the size of an edit, within a stated bound
        V.Cs history = HistoryBuilder.history(cases, "all", 1);
        long start = System.nanoTime();
        V mean =
                Interpreter.eval(
                        ExprParser.parse("history.mean(edit_size)"),
                        new Interpreter.Context(
                                Map.of("history", history),
                                Map.of(
                                        "edit_size",
                                        new Interpreter.Measure(
                                                "edit_size",
                                                "c",
                                                ExprParser.parse(
                                                        "distance(c.proposal.value, c.response.final)"))),
                                Instant.now().toEpochMilli()));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(mean)
                .as("one accepted case (0) and one edited by a sentence (tiny)")
                .isInstanceOf(V.Num.class);
        assertThat(((V.Num) mean).value()).isBetween(0.0, 0.001);
        assertThat(millis).as("edit size of a " + size + " character artifact").isLessThan(15_000);

        // retention: the text is gone from the one place it was kept; what depended on it can no
        // longer be measured
        ledger.purgeFields("Publish", Instant.now().plus(Duration.ofDays(1)));
        assertThat(ledger.recordsOfKind("Publish", Rec.BLOB))
                .allSatisfy(b -> assertThat(b.str("text")).isNull());
        List<LCase> later = LedgerFold.cases(ledger.records("Publish"));
        assertThat(later.get(0).proposalValue()).isNull();
        assertThat(later.get(1).responseFinal()).isNull();
        V none =
                Interpreter.eval(
                        ExprParser.parse("history.mean(edit_size)"),
                        new Interpreter.Context(
                                Map.of("history", HistoryBuilder.history(later, "all", 1)),
                                Map.of(
                                        "edit_size",
                                        new Interpreter.Measure(
                                                "edit_size",
                                                "c",
                                                ExprParser.parse(
                                                        "distance(c.proposal.value, c.response.final)"))),
                                Instant.now().toEpochMilli()));
        assertThat(none)
                .as(
                        "with the text gone the measure is unknown, and an unknown never lets an agent climb")
                .isEqualTo(V.UNDEFINED);
    }

    @org.junit.jupiter.api.Test
    void aLargeArtifactSurvivesTheFileLedgerWholeAndRetentionRemovesItFromTheFile()
            throws Exception {
        String proposed = document(1_000_000);
        Ledger ledger = new FileLedger(dir.resolve("autonomy"));
        DecideHarness h =
                new DecideHarness(dir, ledger, new MemoryLevelStore(), LadderBoundRunTest.PUBLISH);
        h.rawAgent = f -> "{\"post\": \"" + proposed + "\", \"reasoning\": \"draft\"}";
        h.person = f -> "accept";
        h.runCase("p1", h.inputs("x", 1));
        // a new reader of the same files sees the whole text, and the line that refers to it is
        // short
        FileLedger again = new FileLedger(dir.resolve("autonomy"));
        assertThat(LedgerFold.cases(again.records("Publish")).get(0).proposalValue())
                .isEqualTo(proposed);
        long longest =
                java.nio.file.Files.readAllLines(dir.resolve("autonomy/Publish/ledger.jsonl"))
                        .stream()
                        .filter(l -> !l.contains("\"kind\":\"blob\""))
                        .mapToInt(String::length)
                        .max()
                        .orElse(0);
        assertThat(longest).as("every line but the blob's is short").isLessThan(3_000);
        again.purgeFields("Publish", Instant.now().plus(Duration.ofDays(1)));
        assertThat(java.nio.file.Files.readString(dir.resolve("autonomy/Publish/ledger.jsonl")))
                .doesNotContain("word999 ")
                .hasSizeLessThan(20_000);
        assertThat(
                        LedgerFold.cases(new FileLedger(dir.resolve("autonomy")).records("Publish"))
                                .get(0)
                                .proposalValue())
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 16_384})
    void textAtOrUnderTheLimitStaysInTheRecord(int size) {
        MemoryLedger ledger = new MemoryLedger();
        DecideHarness h =
                new DecideHarness(dir, ledger, new MemoryLevelStore(), LadderBoundRunTest.PUBLISH);
        String text = document(size);
        h.rawAgent = f -> "{\"post\": \"" + text + "\", \"reasoning\": \"draft\"}";
        h.person = f -> "accept";
        h.runCase("p1", h.inputs("x", 1));
        assertThat(ledger.recordsOfKind("Publish", Rec.BLOB)).isEmpty();
        assertThat(ledger.recordsOfKind("Publish", Rec.PROPOSED).get(0).str("value"))
                .isEqualTo(text);
        assertThat(Blobs.LIMIT).isEqualTo(16 * 1024);
    }
}
