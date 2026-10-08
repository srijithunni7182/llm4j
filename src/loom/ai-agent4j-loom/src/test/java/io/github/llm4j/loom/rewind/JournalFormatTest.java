package io.github.llm4j.loom.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The boundary list and the step-id rules (spec loom-rewind-and-fork R3), and that a run that never goes back is written as it always was. */
class JournalFormatTest {

    @TempDir
    Path dir;

    @Test
    @Tag("RW-V3.2")
    void aGenerationIsTheHighestStartedAtOrBeforeAStatementInItsBlock() {
        Generations g = new Generations(RunJournal.inMemory());
        assertThat(g.current("W/s", 4)).isEqualTo(1);
        g.start("W/s", 2, "a", "script", "W/s6", "r", Map.of(), "keep", false);
        assertThat(g.current("W/s", 1)).isEqualTo(1);
        assertThat(g.current("W/s", 2)).isEqualTo(2);
        assertThat(g.current("W/s", 9)).isEqualTo(2);
        assertThat(g.current("W/s3/a", 2)).as("another block").isEqualTo(1);
        g.start("W/s", 4, "b", "script", "W/s8", "r", Map.of(), "keep", false);
        assertThat(g.current("W/s", 3)).isEqualTo(2);
        assertThat(g.current("W/s", 4)).isEqualTo(3);
        assertThat(g.next("W/s")).isEqualTo(4);
        assertThat(g.next("W/other")).isEqualTo(2);
        assertThat(g.rewindsBy("W/s6")).isEqualTo(1);
        assertThat(g.total()).isEqualTo(2);
    }

    @Test
    @Tag("RW-V3.3")
    void aBoundaryRecordsEverythingAPersonWouldAskAndSurvivesAReload() {
        RunJournal journal = RunJournal.inMemory();
        Generations g = new Generations(journal);
        g.start("W/s", 1, "collected", "operator:ada", "W/s7", "review.score<7", Map.of("feedback", "fix1"), "keep", true);

        Generations.Boundary b = new Generations(journal).all().get(0);
        assertThat(b.block()).isEqualTo("W/s");
        assertThat(b.from()).isEqualTo(1);
        assertThat(b.generation()).isEqualTo(2);
        assertThat(b.name()).isEqualTo("collected");
        assertThat(b.by()).isEqualTo("operator:ada");
        assertThat(b.statement()).isEqualTo("W/s7");
        assertThat(b.reason()).isEqualTo("review.score<7");
        assertThat(b.carried()).containsEntry("feedback", "fix1");
        assertThat(b.effects()).isEqualTo("keep");
        assertThat(b.askAgain()).isTrue();
        assertThat(b.time()).isNotBlank();
        assertThat(b.fromStep()).isEqualTo("W/s1~2");
    }

    @Test
    @Tag("RW-V3.2")
    void effectsAndAnswersIgnoreTheAttemptUnlessTheAttemptWasToRepeatEffects() {
        Generations g = new Generations(RunJournal.inMemory());
        assertThat(Generations.strip("W/s3~2/a0~4")).isEqualTo("W/s3/a0");
        assertThat(Generations.strip("W/s3/a0")).isEqualTo("W/s3/a0");
        assertThat(g.identity("W/s3~2")).isEqualTo("W/s3");
        g.start("W/s", 3, "a", "script", "W/s9", "r", Map.of(), "repeat", false);
        assertThat(g.identity("W/s3~2")).as("a repeat keeps its attempt, so its effects have keys of their own").isEqualTo("W/s3~2");
        assertThat(g.identity("W/s3~2/a0")).isEqualTo("W/s3~2/a0");
        g.start("W/s", 3, "a", "script", "W/s9", "r", Map.of(), "keep", false);
        assertThat(g.identity("W/s3~3")).isEqualTo("W/s3");
        assertThat(g.identity("W/s3~2")).isEqualTo("W/s3~2");
    }

    @Test
    @Tag("RW-V3.2")
    void aStatementCanBeLocatedByItsIdInTheCurrentAttemptOfEveryBlockAroundIt() {
        Generations g = new Generations(RunJournal.inMemory());
        g.start("W/s", 2, "a", "script", null, "r", Map.of(), "keep", false);
        g.start("W/s3~2/a", 1, "b", "script", null, "r", Map.of(), "keep", false);
        var at = g.locate("W/s3/a1");
        assertThat(at.block()).isEqualTo("W/s3~2/a");
        assertThat(at.index()).isEqualTo(1);
        assertThat(at.liveId()).isEqualTo("W/s3~2/a1~2");
        assertThat(g.locate("W/s1").liveId()).isEqualTo("W/s1");
        assertThat(g.locate("W/s2/e0/b0")).as("a branch of a for each").isNull();
        assertThat(g.locate("W")).isNull();
        assertThat(g.locate("W>Other/s0")).as("a call into another workflow").isNull();

        assertThat(g.isCurrent("W/s3")).isFalse();
        assertThat(g.isCurrent("W/s3~2")).isTrue();
        assertThat(g.isCurrent("W/s3~2/a1")).isFalse();
        assertThat(g.isCurrent("W/s3~2/a1~2")).isTrue();
        assertThat(g.isCurrent("W/s1")).isTrue();
    }

    @Test
    @Tag("RW-V3.7")
    void aRunThatWasRewoundByADifferentFormatIsRefusedInPlainWords() {
        RunJournal journal = RunJournal.inMemory();
        Map<String, Object> future = new LinkedHashMap<>();
        future.put("version", 99);
        future.put("boundaries", java.util.List.of());
        journal.put("#boundaries", new RunJournal.Entry("boundaries", future));
        assertThatThrownBy(() -> new Generations(journal)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different version of weave").hasMessageContaining("99");

        RunJournal old = RunJournal.inMemory();
        old.put("#boundaries", new RunJournal.Entry("boundaries", java.util.List.of()));
        assertThatThrownBy(() -> new Generations(old)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Tag("RW-V3.2")
    @Tag("RW-V9.2")
    void aScriptThatNeverUsesTheFeatureWritesNoNewKindOfEntry() {
        RunJournal journal = RunJournal.inMemory();
        ScriptedRun run = new ScriptedRun(dir);
        run.journal = journal;
        run.responder = r -> {
            String message = ScriptedRun.lastMessage(r);
            if (message.lastIndexOf("Observation:") > message.lastIndexOf("Current Task:")) return ScriptedRun.done("sent");
            return ScriptedRun.call("Log", "{\"action\": \"append\", \"path\": \"n.md\", \"content\": \"x\"}");
        };
        var executor = run.executor("""
                tool Log { use: file  root: "out"  mode: write }
                agent A { model: "m" system: "You are A." tools: [Log] approve: [Log] max_iterations: 6 }
                workflow W() {
                    human_prompt "Go?" -> go
                    delegate "Send" to A -> r
                    loop until (r == "sent") max 2 { delegate "again" to A -> r }
                }
                """);
        executor.initialize();
        executor.executeWorkflow("W", Map.of());

        assertThat(journal.all().keySet()).noneMatch(k -> k.contains("#boundaries") || k.contains("#checkpoint") || k.contains("#asked") || k.contains("~") || k.contains("#rewind"));
        assertThat(journal.all().keySet()).anyMatch(k -> k.contains("#effect:")).anyMatch(k -> k.contains("#approve:")).contains("W/s0", "W/s1");
    }

    @Test
    @Tag("RW-V1.3")
    @Tag("RW-V1.4")
    @Tag("RW-V7.1")
    void reachingACheckpointRecordsItOnceCostsNoModelCallAndSetsItsValues() {
        ReportScript model = new ReportScript(9);
        RunJournal journal = RunJournal.inMemory();
        ScriptedRun run = new ScriptedRun(dir);
        run.responder = model::reply;
        run.journal = journal;
        var executor = run.executor("""
                agent Writer { model: "m" system: "You are Writer." }
                workflow W(topic) {
                    checkpoint a  starting with feedback = "none", subject = "about {topic}"
                    delegate "Write. Feedback: {feedback} {subject}" to Writer -> w
                }
                """);
        executor.initialize();
        executor.executeWorkflow("W", Map.of("topic", "bees"));

        assertThat(journal.get("W/s0#checkpoint")).isPresent();
        assertThat(journal.get("W/s0#checkpoint").get().value().toString()).contains("name=a").contains("generation=1");
        assertThat(model.calls).containsExactly("Writer");
        assertThat(model.tasks.get(0)).contains("Feedback: none about bees");
        assertThat(run.audit).contains("checkpoint_reached");
        assertThat(run.trace.stream().map(t -> t.type()).toList()).contains("checkpoint");
        String before = journal.get("W/s0#checkpoint").get().value().toString();

        ScriptedRun again = new ScriptedRun(dir);
        again.responder = model::reply;
        again.journal = journal;
        var e2 = again.executor("""
                agent Writer { model: "m" system: "You are Writer." }
                workflow W(topic) {
                    checkpoint a  starting with feedback = "none", subject = "about {topic}"
                    delegate "Write. Feedback: {feedback} {subject}" to Writer -> w
                }
                """);
        e2.initialize();
        e2.executeWorkflow("W", Map.of("topic", "bees"));
        assertThat(journal.get("W/s0#checkpoint").get().value().toString()).as("not written twice").isEqualTo(before);
        assertThat(model.calls).hasSize(1);
    }

    @Test
    @Tag("RW-V1.5")
    void aCheckpointInALoopBodyBelongsToItsRound() {
        ReportScript model = new ReportScript(9);
        RunJournal journal = RunJournal.inMemory();
        ScriptedRun run = new ScriptedRun(dir);
        run.responder = model::reply;
        run.journal = journal;
        var executor = run.executor("""
                agent Writer { model: "m" system: "You are Writer." }
                workflow W() {
                    loop until (w == "never") max 2 {
                        checkpoint inround
                        delegate "Write" to Writer -> w
                    }
                }
                """);
        executor.initialize();
        executor.executeWorkflow("W", Map.of());

        assertThat(journal.all().keySet()).contains("W/s0/r1.0#checkpoint", "W/s0/r2.0#checkpoint");
    }
}
