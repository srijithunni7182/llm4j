package io.github.llm4j.loom.explain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.ObserveReplay;
import io.github.llm4j.loom.travel.TravelFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R2 of spec 13: where the journal and the script stop agreeing is said, and nothing is invented.
 */
class DivergenceTest {

    @TempDir Path dir;

    private TravelFixtures.Case named(String name) {
        return TravelFixtures.CORPUS.stream()
                .filter(x -> x.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aJournalWithAHoleInTheMiddleIsReportedAsDivergedAtTheHole() throws Exception {
        var c = named("loop until done");
        var real = TravelFixtures.run(dir, c);
        RunJournal pruned = RunJournal.inMemory();
        var all = real.run().journal().all();
        String hole =
                all.keySet().stream().filter(k -> k.contains("r2.0")).findFirst().orElseThrow();
        all.forEach(
                (k, v) -> {
                    if (!k.equals(hole)) pruned.put(k, v);
                });
        Why.Report r =
                Why.explain(real.script(), "Main", c.input(), pruned, Why.Request.path(), Set.of());
        assertThat(r.notes())
                .anyMatch(
                        n ->
                                n.startsWith("Diverged at")
                                        && n.contains("the journal goes on past it"));
        assertThat(r.status()).isEqualTo("needs");
        // what came before the hole is still explained exactly
        assertThat(r.entries()).isNotEmpty();
        assertThat(WhyRenderer.text(r)).contains("loop until (done == \"yes\")");
    }

    @Test
    void aJournalThatEndsWhereTheRunEndedIsNotADivergence() throws Exception {
        var c = named("human answer");
        var waiting =
                new TravelFixtures.Case(
                        "waiting", c.script(), "Main", c.input(), t -> t.model("A", "a draft"));
        var real = TravelFixtures.run(dir, waiting);
        ObserveReplay.Result seen =
                ObserveReplay.run(
                        new ObserveReplay.Options(real.script(), "Main", c.input(), null),
                        real.run().journal());
        assertThat(seen.divergence()).isNull();
    }

    @Test
    void aScriptChangedSoAStepIsNoLongerReachedIsSaidToHaveUnreachedEntries() throws Exception {
        var c = named("triage order status");
        var real = TravelFixtures.run(dir, c);
        // the order-status branch is now taken for another label: the delegate's journal entry is
        // not reached
        Files.writeString(
                real.script(),
                Files.readString(real.script())
                        .replace("intent.kind == \"order_status\"", "intent.kind == \"refund\""));
        Why.Report r =
                Why.explain(
                        real.script(),
                        "Main",
                        c.input(),
                        real.run().journal(),
                        Why.Request.path(),
                        Set.of());
        assertThat(r.notes())
                .anyMatch(n -> n.contains("Diverged at") && n.contains("no longer reaches"));
    }

    @Test
    void aScriptChangedSoAStepIsADifferentKindIsCaughtByTheEntryKind() throws Exception {
        var c = named("straight");
        var real = TravelFixtures.run(dir, c);
        Files.writeString(
                real.script(),
                Files.readString(real.script())
                        .replace(
                                "delegate \"Write about {topic}\" to A -> draft",
                                "note \"hello\"\n    set draft = \"x\""));
        Why.Report r =
                Why.explain(
                        real.script(),
                        "Main",
                        c.input(),
                        real.run().journal(),
                        Why.Request.path(),
                        Set.of());
        assertThat(r.notes())
                .anyMatch(
                        n ->
                                n.contains("Diverged at Main/s0")
                                        && n.contains("holds a delegate result"));
    }

    @Test
    void aReplayNeverInventsAValue() throws Exception {
        var c = named("straight");
        var real = TravelFixtures.run(dir, c);
        ObserveReplay.Result seen =
                ObserveReplay.run(
                        new ObserveReplay.Options(real.script(), "Main", c.input(), null),
                        RunJournal.inMemory());
        assertThat(seen.variables().get("draft")).isNull();
        assertThat(seen.variables().get("result")).isNull();
    }
}
