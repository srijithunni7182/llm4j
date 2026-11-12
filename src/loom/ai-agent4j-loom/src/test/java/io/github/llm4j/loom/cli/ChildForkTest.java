package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ChildRuns;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 33b, D3: copying a run copies its children, and the copy never shares one with the original. */
class ChildForkTest {

    @TempDir Path dir;

    @Test
    void aForkGetsItsOwnCopiesOfTheChildrenAndOfTheirChildrenUnderTheIdsItWillLookFor() {
        Path original = dir.resolve("r1");
        Path copy = dir.resolve("r2");
        String originalId = original.toAbsolutePath().normalize().toString();
        var originalRuns = new FolderChildRuns(original);
        // the original started one child at Main/s0, and that child started one of its own
        String childId = ChildRuns.idOf(originalId, "Main/s0");
        String grandId = ChildRuns.idOf(childId, "Child/s1");
        RunJournal journal = new FileRunJournal(original.resolve("journal.json"));
        journal.put("Main/s0", new RunJournal.Entry("child", Map.of("run", childId, "workflow", "Child")));
        originalRuns.record(childId, originalId, "Child");
        originalRuns.openJournal(childId).put("Child/s1", new RunJournal.Entry("child", Map.of("run", grandId, "workflow", "Leaf")));
        originalRuns.openJournal(childId).put("Child/s0", new RunJournal.Entry("task", "done"));
        originalRuns.record(grandId, childId, "Leaf");
        originalRuns.openJournal(grandId).put("Leaf/s0", new RunJournal.Entry("task", "leaf"));

        RunJournal copied = new FileRunJournal(copy.resolve("journal.json"));
        journal.all().forEach(copied::put);
        int n = ChildFork.copy(original, copy, copied);

        String newId = ChildRuns.idOf(copy.toAbsolutePath().normalize().toString(), "Main/s0");
        assertThat(n).isEqualTo(2);
        assertThat(copied.get("Main/s0").orElseThrow().value()).asString().contains(newId);
        var copyRuns = new FolderChildRuns(copy);
        assertThat(copyRuns.openJournal(newId).get("Child/s0")).isPresent();
        String newGrand = ChildRuns.idOf(newId, "Child/s1");
        assertThat(copyRuns.openJournal(newGrand).get("Leaf/s0")).isPresent();
        assertThat(copyRuns.openJournal(newId).get("Child/s1").orElseThrow().value()).asString().contains(newGrand);
        // and the original is untouched
        assertThat(journal.get("Main/s0").orElseThrow().value()).asString().contains(childId);
        assertThat(originalRuns.openJournal(childId).get("Child/s1").orElseThrow().value()).asString().contains(grandId);
    }

    @Test
    void theTimelineDrawsChildrenAsATreeUnderTheirParentWithTheirOwnTimeline() {
        Path run = dir.resolve("r3");
        String id = run.toAbsolutePath().normalize().toString();
        String childId = ChildRuns.idOf(id, "Main/s0");
        RunJournal journal = new FileRunJournal(run.resolve("journal.json"));
        journal.put("Main/s0", new RunJournal.Entry("child", Map.of("run", childId, "workflow", "Research")));
        var runs = new FolderChildRuns(run);
        runs.record(childId, id, "Research");
        runs.openJournal(childId).put("Research/s0", new RunJournal.Entry("task", "found"));

        String text = ChildFork.tree(run, journal);

        assertThat(text).contains("child Research (step Main/s0").contains("Research/s0");
    }
}
