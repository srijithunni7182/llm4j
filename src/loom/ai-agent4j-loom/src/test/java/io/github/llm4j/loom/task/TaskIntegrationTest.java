package io.github.llm4j.loom.task;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.cli.ScriptDrift;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.RunTravel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where tasks meet the rest of the operator tooling: the run timeline and script drift checks. */
@Tag("integration")
class TaskIntegrationTest {

    @TempDir
    Path dir;

    private final TaskHarness h = new TaskHarness();

    @Test
    void travelTimelineShowsTaskSteps() {
        h.pure("A", c -> TaskResult.ok());
        h.changes("Pay", c -> TaskResult.ok());
        h.run("workflow Main() { run A() -> a  run Pay(x = 1) -> b }");
        RunTravel.Timeline timeline = RunTravel.timeline(h.journal);
        assertEquals(2, timeline.steps().size(), timeline.steps().toString());
        assertEquals("task", timeline.steps().get(0).kind());
        assertEquals("Main/s0", timeline.steps().get(0).id());
        assertEquals("done", timeline.steps().get(0).state());
        assertEquals(0, timeline.steps().get(0).tokens(), "a task spends no tokens");
        assertEquals("task", timeline.steps().get(1).kind());
    }

    @Test
    void travelTimelineShowsAFailedTaskStep() {
        h.pure("Boom", c -> { throw new IllegalStateException("x"); });
        h.run("workflow Main() { run Boom() -> a on_failure { note \"handled\" } }");
        assertEquals("failed", RunTravel.timeline(h.journal).steps().stream().filter(r -> r.id().equals("Main/s0")).findFirst().orElseThrow().state());
    }

    @Test
    void scriptDriftSeesAChangedTaskCallBeforeTheForkPoint() throws Exception {
        Path original = dir.resolve("a.loom");
        Path same = dir.resolve("b.loom");
        Path changedArgs = dir.resolve("c.loom");
        Path changedTask = dir.resolve("d.loom");
        String base = "workflow Main() { run Policy(amount = %s) -> v  run Pay(amount = 40) -> r }";
        Files.writeString(original, base.formatted("40"));
        Files.writeString(same, base.formatted("40"));
        Files.writeString(changedArgs, base.formatted("400"));
        Files.writeString(changedTask, base.formatted("40").replace("run Policy", "run LaxPolicy"));
        RunJournal journal = RunJournal.inMemory();

        assertNull(ScriptDrift.between(original, same, journal, "Main", null));
        String args = ScriptDrift.between(original, changedArgs, journal, "Main", null);
        assertNotNull(args, "a different amount before the fork point is drift");
        assertTrue(args.contains("task Policy"), args);
        String task = ScriptDrift.between(original, changedTask, journal, "Main", null);
        assertNotNull(task);
        assertTrue(task.contains("LaxPolicy"), task);
        // a change after the fork point is fine
        assertNull(ScriptDrift.between(original, changedArgs, journal, "Main", "Main/s0"),
                "only statements before the fork point count; here the fork is before the changed call");
    }
}
