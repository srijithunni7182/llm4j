package io.github.llm4j.loom.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.TravelFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R18: where the stepper stops on its own. */
class BreakpointTest {

    @TempDir Path dir;

    Stepper stepper;

    @AfterEach
    void close() {
        if (stepper != null) stepper.close();
    }

    private void open(String name) throws Exception {
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
    }

    @Test
    void aLineBreakpointStopsBeforeTheStatementWrittenThere() throws Exception {
        open("triage order status");
        var b = stepper.breakAt("15");
        assertThat(b.line()).isEqualTo(15);
        var s = stepper.move(Stepper.Move.CONTINUE);
        assertThat(s.finished()).isFalse();
        assertThat(s.position().line()).isEqualTo(15);
        assertThat(s.hit()).startsWith("breakpoint 1");
    }

    @Test
    void aLineWithNoStatementStopsAtTheNextOne() throws Exception {
        open("triage order status");
        var b = stepper.breakAt("12");
        assertThat(b.line()).isGreaterThanOrEqualTo(12);
        assertThat(stepper.move(Stepper.Move.CONTINUE).position().line()).isEqualTo(b.line());
    }

    @Test
    void aLineAfterTheLastStatementIsRefused() throws Exception {
        open("straight");
        assertThatThrownBy(() -> stepper.breakAt("999"))
                .hasMessageContaining("no statement at or after line 999");
    }

    @Test
    void anAgentBreakpointStopsBeforeEachStatementForThatAgent() throws Exception {
        open("loop until done");
        stepper.breakAt("Checker");
        var first = stepper.move(Stepper.Move.CONTINUE);
        assertThat(first.position().statement()).isEqualTo("delegate Checker");
        var second = stepper.move(Stepper.Move.CONTINUE);
        assertThat(second.position().statement()).isEqualTo("delegate Checker");
        assertThat(second.position().index()).isGreaterThan(first.position().index());
    }

    @Test
    void aNodeBreakpointStopsAtThatNode() throws Exception {
        open("triage order status");
        var s0 = stepper.state();
        // the second statement of the workflow
        stepper.breakAt("n2");
        var s = stepper.move(Stepper.Move.CONTINUE);
        assertThat(s.position().node()).isEqualTo("n2");
        assertThat(s0.position().node()).isEqualTo("n1");
    }

    @Test
    void aConditionBreakpointStopsWhenItHoldsAndNotBefore() throws Exception {
        open("triage order status");
        stepper.breakAt("when reply == \"[mock answer]\"");
        var s = stepper.move(Stepper.Move.CONTINUE);
        assertThat(s.finished()).isFalse();
        assertThat(s.variables()).anyMatch(v -> v.name().equals("reply"));
        assertThat(s.position().kind()).isEqualTo("return");
        assertThat(s.hit()).contains("when reply");
    }

    @Test
    void aConditionThatNeverHoldsLetsTheRunFinish() throws Exception {
        open("straight");
        stepper.breakAt("when topic == \"dogs\"");
        assertThat(stepper.move(Stepper.Move.CONTINUE).finished()).isTrue();
    }

    @Test
    void anInvalidConditionIsRejectedWithWhatIsAccepted() throws Exception {
        open("straight");
        assertThatThrownBy(() -> stepper.breakAt("when ((("))
                .hasMessageContaining("is not a condition the language knows")
                .hasMessageContaining("intent.urgent == \"yes\"");
        assertThatThrownBy(() -> stepper.breakAt("when ")).hasMessageContaining("is empty");
    }

    @Test
    void somethingThatIsNotABreakpointIsRefusedWithTheForms() throws Exception {
        open("straight");
        assertThatThrownBy(() -> stepper.breakAt("Nobody"))
                .hasMessageContaining("is not a line, a node (n3), an agent, a classifier");
        assertThatThrownBy(() -> stepper.breakAt("n99")).hasMessageContaining("no node n99");
    }

    @Test
    void aClassifierIsAnAgentForBreakpoints() throws Exception {
        open("triage order status");
        stepper.breakAt("Intent");
        // the first statement is already the classify; stepping on and continuing finds no more
        stepper.move(Stepper.Move.CONTINUE);
        assertThat(stepper.state().finished()).isTrue();
    }

    @Test
    void removedBreakpointsStopStopping() throws Exception {
        open("loop until done");
        var b = stepper.breakAt("Checker");
        stepper.breakpoints().remove(b.id());
        assertThat(stepper.move(Stepper.Move.CONTINUE).finished()).isTrue();
    }
}
