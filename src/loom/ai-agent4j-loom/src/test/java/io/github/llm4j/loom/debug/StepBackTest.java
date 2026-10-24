package io.github.llm4j.loom.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.VariableView;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.TravelFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** R23: going back re-derives the state exactly as it was going forward. */
class StepBackTest {

    @TempDir Path dir;

    Stepper stepper;

    @AfterEach
    void close() {
        if (stepper != null) stepper.close();
    }

    static Stream<String> cases() {
        return Stream.of(
                "straight", "triage order status", "loop until done", "for each", "loop runs out");
    }

    /** What a person sees at a pause, reduced to what must be the same each time. */
    private record Seen(int index, String step, String statement, List<String> variables) {
        static Seen of(Stepper.State s) {
            List<String> vars = new ArrayList<>();
            for (VariableView v : s.variables())
                vars.add(v.name() + "=" + v.value() + "|" + v.boundStep() + "|" + v.boundBy());
            return new Seen(
                    s.position().index(), s.position().step(), s.position().statement(), vars);
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void everyStateGoingBackIsTheOneGoingForward(String name) throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals(name))
                        .findFirst()
                        .orElseThrow();
        Path file = dir.resolve("s.loom");
        Files.writeString(file, c.script());
        stepper =
                Stepper.open(
                        new Stepper.Config(
                                file,
                                c.workflow(),
                                c.input(),
                                Set.of(),
                                RunJournal.inMemory(),
                                null));
        List<Seen> forward = new ArrayList<>();
        Stepper.State s = stepper.state();
        while (!s.finished()) {
            forward.add(Seen.of(s));
            s = stepper.move(Stepper.Move.INTO);
        }
        assertThat(forward).hasSizeGreaterThan(1);
        // from the end, back to the last statement, and so on to the first
        for (int i = forward.size() - 1; i >= 0; i--) {
            s = stepper.back();
            assertThat(Seen.of(s)).isEqualTo(forward.get(i));
        }
        assertThatThrownBy(stepper::back).hasMessageContaining("first statement");
    }

    @Test
    void backThenOnGivesTheSameRun() throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals("loop until done"))
                        .findFirst()
                        .orElseThrow();
        Path file = dir.resolve("s.loom");
        Files.writeString(file, c.script());
        stepper =
                Stepper.open(
                        new Stepper.Config(
                                file,
                                c.workflow(),
                                c.input(),
                                Set.of(),
                                RunJournal.inMemory(),
                                null));
        stepper.model("Checker", "no");
        stepper.move(Stepper.Move.INTO);
        stepper.move(Stepper.Move.INTO);
        var here = Seen.of(stepper.state());
        stepper.back();
        stepper.back();
        stepper.move(Stepper.Move.INTO);
        assertThat(Seen.of(stepper.move(Stepper.Move.INTO))).isEqualTo(here);
    }

    @Test
    void backDoesNotWriteTheJournalItStartedFrom() throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals("straight"))
                        .findFirst()
                        .orElseThrow();
        var real = TravelFixtures.run(dir, c);
        var before = java.util.Map.copyOf(real.run().journal().all());
        stepper =
                Stepper.open(
                        new Stepper.Config(
                                real.script(),
                                "Main",
                                c.input(),
                                Set.of(),
                                real.run().journal(),
                                null));
        stepper.move(Stepper.Move.CONTINUE);
        stepper.back();
        stepper.move(Stepper.Move.CONTINUE);
        assertThat(real.run().journal().all()).isEqualTo(before);
    }
}
