package io.github.llm4j.loom.travel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.DecisionPoint;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R1, R43 of spec 13: for every run in the corpus, replaying its journal with nothing that has an
 * effect starts the same steps, in the same order, and ends the same way with the same result.
 */
class ObserveReplayEqualsRunTest {

    @TempDir Path dir;

    static ObserveReplay.Result replay(TravelFixtures.Run r) throws Exception {
        return ObserveReplay.run(
                new ObserveReplay.Options(
                        r.script(), r.source().workflow(), r.source().input(), null),
                r.run().journal());
    }

    @Test
    void everyRunOfTheCorpusIsRederivedStepForStep() throws Exception {
        for (TravelFixtures.Case c : TravelFixtures.CORPUS) {
            TravelFixtures.Run real = TravelFixtures.run(dir, c);
            ObserveReplay.Result seen = replay(real);
            assertThat(seen.steps()).as(c.name() + ": steps").isEqualTo(real.run().stepIds());
            assertThat(seen.status()).as(c.name() + ": status").isEqualTo(real.run().status());
            assertThat(seen.variables().get("result"))
                    .as(c.name() + ": result")
                    .isEqualTo(real.run().result());
            assertThat(seen.unvisited()).as(c.name() + ": journal entries not reached").isEmpty();
            assertThat(seen.divergence()).as(c.name() + ": divergence").isNull();
        }
    }

    @Test
    void theJournalItWasGivenIsNotWritten() throws Exception {
        TravelFixtures.Run real = TravelFixtures.run(dir, TravelFixtures.CORPUS.get(1));
        var before = real.run().journal().all();
        replay(real);
        assertThat(real.run().journal().all()).isEqualTo(before);
    }

    @Test
    void aRunThatWaitedForAPersonReplaysAsWaiting() throws Exception {
        // the same script, run with no human answer: the real run is suspended at the question
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals("human answer"))
                        .findFirst()
                        .get();
        var waiting =
                new TravelFixtures.Case(
                        c.name() + " unanswered",
                        c.script(),
                        c.workflow(),
                        c.input(),
                        t -> t.model("A", "a draft"));
        TravelFixtures.Run real = TravelFixtures.run(dir, waiting);
        assertThat(real.run().status()).isEqualTo("suspended");
        ObserveReplay.Result seen = replay(real);
        assertThat(seen.status()).isEqualTo("suspended");
        assertThat(seen.reason()).contains("Approve a draft?");
        assertThat(seen.steps()).isEqualTo(real.run().stepIds());
    }

    @Test
    void decisionsAreFoundForEveryKindOfChoice() throws Exception {
        List<String> kinds = new java.util.ArrayList<>();
        for (TravelFixtures.Case c : TravelFixtures.CORPUS)
            replay(TravelFixtures.run(dir, c)).decisions().forEach(d -> kinds.add(d.kind()));
        assertThat(kinds)
                .contains("alt", "loop", "for each", "classify", "rewind", "failure")
                .doesNotContainNull();
    }

    @Test
    void aReplayNeverCallsAnything() throws Exception {
        // a journal with nothing in it: the very first step needs a model, and the replay says so
        // instead of calling one
        TravelFixtures.Run real = TravelFixtures.run(dir, TravelFixtures.CORPUS.get(0));
        ObserveReplay.Result seen =
                ObserveReplay.run(
                        new ObserveReplay.Options(
                                real.script(), "Main", real.source().input(), null),
                        io.github.llm4j.loom.runtime.RunJournal.inMemory());
        assertThat(seen.status()).isEqualTo("needs");
        assertThat(seen.reason()).isEqualTo("a model answer from A");
        assertThat(seen.stoppedAt()).isEqualTo("Main/s0");
        assertThat(seen.decisions()).isEmpty();
        DecisionPoint none = null;
        assertThat(none).isNull();
    }
}
