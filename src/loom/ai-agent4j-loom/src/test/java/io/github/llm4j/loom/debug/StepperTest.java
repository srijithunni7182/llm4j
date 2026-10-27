package io.github.llm4j.loom.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.TravelFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R14 to R16, R21, R22: stepping a workflow one statement at a time. */
class StepperTest {

    @TempDir Path dir;

    Stepper stepper;

    @AfterEach
    void close() {
        if (stepper != null) stepper.close();
    }

    Stepper open(String script, String workflow, Map<String, String> inputs) throws Exception {
        Path file = dir.resolve("s.loom");
        Files.writeString(file, script);
        stepper =
                Stepper.open(
                        new Stepper.Config(
                                file, workflow, inputs, Set.of(), RunJournal.inMemory(), null));
        return stepper;
    }

    Stepper open(String caseName) throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals(caseName))
                        .findFirst()
                        .orElseThrow();
        return open(c.script(), c.workflow(), c.input());
    }

    @Test
    void pausesBeforeTheFirstStatementWithTheInputsInScope() throws Exception {
        open("straight");
        var s = stepper.state();
        assertThat(s.finished()).isFalse();
        assertThat(s.hit()).isEqualTo("start");
        assertThat(s.position().statement()).isEqualTo("delegate A");
        assertThat(s.position().kind()).isEqualTo("delegate");
        assertThat(s.position().line()).isEqualTo(3);
        assertThat(s.position().index()).isZero();
        assertThat(s.variables()).extracting("name").containsExactly("topic");
        assertThat(s.variables().get(0).value()).isEqualTo("cats");
        assertThat(s.variables().get(0).boundBy()).isNull();
    }

    @Test
    void nextRunsTheStatementAndPausesAtTheFollowingOne() throws Exception {
        open("straight");
        var s = stepper.move(Stepper.Move.INTO);
        assertThat(s.position().kind()).isEqualTo("return");
        // the delegate has run: its reply is bound, with the statement that bound it
        var draft =
                s.variables().stream()
                        .filter(v -> v.name().equals("draft"))
                        .findFirst()
                        .orElseThrow();
        assertThat(draft.value()).isEqualTo("[mock answer]");
        assertThat(draft.boundBy()).isEqualTo("delegate A");
        assertThat(draft.type()).isEqualTo("text");
        s = stepper.move(Stepper.Move.INTO);
        assertThat(s.finished()).isTrue();
        assertThat(s.status()).isEqualTo("completed");
        assertThat(s.result()).isEqualTo("[mock answer]");
    }

    @Test
    void anAltPausesInsideItsBranchWithIntoAndPassesOverItWithNext() throws Exception {
        open("triage order status");
        stepper.move(Stepper.Move.INTO); // the classify has run
        assertThat(stepper.state().position().kind()).isEqualTo("alt");
        assertThat(stepper.state().position().statement()).startsWith("intent.urgent");
        // classifiers answer their first label, so the mock run is not urgent
        var into = stepper.move(Stepper.Move.INTO);
        assertThat(into.position().statement()).startsWith("intent.kind");
        assertThat(into.position().enclosing()).hasSize(1);
        assertThat(into.position().enclosing().get(0)).contains("intent.urgent");
    }

    @Test
    void overRunsTheWholeCompoundStatement() throws Exception {
        open("loop until done");
        var loop = stepper.state();
        assertThat(loop.position().statement()).startsWith("loop until");
        var after = stepper.move(Stepper.Move.OVER);
        // the mock never says "yes", so the loop ran out its five rounds and the run went on
        assertThat(after.position() == null ? after.status() : after.position().statement())
                .doesNotContain("delegate Checker");
    }

    @Test
    void outLeavesTheBlockTheStatementIsIn() throws Exception {
        open("loop until done");
        stepper.move(Stepper.Move.INTO); // into the body
        assertThat(stepper.state().position().statement()).isEqualTo("delegate Checker");
        var out = stepper.move(Stepper.Move.OUT);
        assertThat(out.finished() || !out.position().statement().equals("delegate Checker"))
                .isTrue();
    }

    @Test
    void continueRunsToTheEnd() throws Exception {
        open("for each");
        var end = stepper.move(Stepper.Move.CONTINUE);
        assertThat(end.finished()).isTrue();
        assertThat(end.status()).isIn("completed", "failed");
    }

    @Test
    void whereShowsTheBlocksAndWhatFollows() throws Exception {
        open("triage order status");
        var s = stepper.state();
        assertThat(s.position().after()).isNotEmpty();
        assertThat(s.position().after().get(0)).contains("intent.urgent");
    }

    @Test
    void settingAVariableChangesWhatFollowsAndSurvivesGoingBack() throws Exception {
        open(
                """
                agent A { model: "m" system: "x" }
                workflow Main(word) {
                    alt (word == "yes") {
                        return "said yes"
                    } else {
                        return "said no"
                    }
                }
                """,
                "Main",
                Map.of("word", "no"));
        stepper.set("word", "yes");
        assertThat(stepper.state().variables().get(0).value()).isEqualTo("yes");
        var into = stepper.move(Stepper.Move.INTO);
        assertThat(into.position().kind()).isEqualTo("return");
        assertThat(into.position().enclosing()).hasSize(1);
        // back, then on again: the edit is still there
        stepper.back();
        assertThat(stepper.state().position().kind()).isEqualTo("alt");
        assertThat(stepper.state().variables().get(0).value()).isEqualTo("yes");
        assertThat(stepper.move(Stepper.Move.CONTINUE).result()).isEqualTo("said yes");
    }

    @Test
    void noVariableNameIsRefusedWithAReason() throws Exception {
        open("straight");
        assertThatThrownBy(() -> stepper.set("not a name", "x"))
                .hasMessageContaining("not a variable name");
    }

    @Test
    void scriptedModelAnswerIsUsedAndKeptWhenGoingBackAndOn() throws Exception {
        open("straight");
        stepper.model("A", "a scripted draft");
        var after = stepper.move(Stepper.Move.INTO);
        assertThat(value(after, "draft")).isEqualTo("a scripted draft");
        stepper.back();
        var again = stepper.move(Stepper.Move.INTO);
        assertThat(value(again, "draft")).isEqualTo("a scripted draft");
    }

    @Test
    void aScriptedClassifierAnswerSendsTheRunDownTheOtherBranch() throws Exception {
        open("triage order status");
        stepper.classifier("Intent", "{\"kind\":\"refund\",\"urgent\":\"yes\"}");
        stepper.move(Stepper.Move.INTO);
        var s = stepper.move(Stepper.Move.INTO);
        assertThat(s.position().kind()).isEqualTo("note");
    }

    @Test
    void aTypedAnswerForAnAgentWithASchemaMakesAForEachWalkItsItems() throws Exception {
        open("for each");
        stepper.model("Planner", "{\"items\": [\"a\", \"b\"]}");
        stepper.model("Worker", "done a");
        stepper.model("Worker", "done b");
        var end = stepper.move(Stepper.Move.CONTINUE);
        assertThat(end.status()).isEqualTo("completed");
        assertThat(end.result()).isEqualTo("done b");
    }

    @Test
    void anUnknownAgentOrClassifierIsRefused() throws Exception {
        open("straight");
        assertThatThrownBy(() -> stepper.model("Nobody", "x")).hasMessageContaining("not an agent");
        assertThatThrownBy(() -> stepper.classifier("Nobody", "{}"))
                .hasMessageContaining("not a classifier");
    }

    @Test
    void aWorkflowThatWaitsForAPersonIsAnsweredAndPausedBeforeThatStatementAgain()
            throws Exception {
        open("human answer");
        var s = stepper.move(Stepper.Move.CONTINUE);
        assertThat(s.finished()).isTrue();
        assertThat(s.status()).isEqualTo("suspended");
        var back = stepper.answer("yes");
        assertThat(back.finished()).isFalse();
        assertThat(back.position().kind()).isEqualTo("human_prompt");
        var end = stepper.move(Stepper.Move.CONTINUE);
        assertThat(end.status()).isEqualTo("completed");
        assertThat(end.result()).isEqualTo("published");
    }

    @Test
    void answerWithNothingWaitingIsRefused() throws Exception {
        open("straight");
        assertThatThrownBy(() -> stepper.answer("x")).hasMessageContaining("Nothing is waiting");
    }

    @Test
    void movingAfterTheEndIsRefusedWithAWayOut() throws Exception {
        open("straight");
        stepper.move(Stepper.Move.CONTINUE);
        assertThatThrownBy(() -> stepper.move(Stepper.Move.INTO)).hasMessageContaining("has ended");
    }

    @Test
    void theOriginalJournalIsNotWritten() throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals("straight"))
                        .findFirst()
                        .orElseThrow();
        var real = TravelFixtures.run(dir, c);
        RunJournal original = real.run().journal();
        var before = Map.copyOf(original.all());
        stepper =
                Stepper.open(
                        new Stepper.Config(
                                real.script(), "Main", c.input(), Set.of(), original, null));
        // stepping a finished run shows what it did: the reply of its recording
        var s = stepper.move(Stepper.Move.INTO);
        assertThat(value(s, "draft")).isEqualTo("a draft");
        stepper.move(Stepper.Move.CONTINUE);
        assertThat(original.all()).isEqualTo(before);
    }

    @Test
    void maskedVariablesAreNeverShown() throws Exception {
        Path file = dir.resolve("m.loom");
        Files.writeString(file, TravelFixtures.CORPUS.get(0).script());
        stepper =
                Stepper.open(
                        new Stepper.Config(
                                file,
                                "Main",
                                Map.of("topic", "secret cats"),
                                Set.of("topic"),
                                RunJournal.inMemory(),
                                null));
        assertThat(stepper.state().variables().get(0).value()).isEqualTo("‹masked›");
    }

    @Test
    void liveModelsAreNotCalledAndNothingIsSpentByAMockRun() throws Exception {
        open("straight");
        var end = stepper.move(Stepper.Move.CONTINUE);
        assertThat(end.calls()).isEqualTo(1); // the mock was asked once; it costs nothing
        assertThat(end.tokens()).isLessThanOrEqualTo(15);
    }

    private static String value(Stepper.State s, String name) {
        return s.variables().stream()
                .filter(v -> v.name().equals(name))
                .map(v -> v.value())
                .findFirst()
                .orElse(null);
    }
}
