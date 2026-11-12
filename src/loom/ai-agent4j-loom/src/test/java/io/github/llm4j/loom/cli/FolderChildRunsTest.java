package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 33b: under {@code weave run --journal DIR} a child is a sibling folder with its own journal. */
class FolderChildRunsTest {

    @TempDir Path dir;

    @Test
    void aChildIsASiblingFolderOfTheParentsAndTheSameIdGivesTheSameFolder() {
        var runs = new FolderChildRuns(dir.resolve("r1"));

        assertThat(runs.folderOf("a").getParent()).isEqualTo(dir);
        assertThat(runs.folderOf("a")).isEqualTo(runs.folderOf("a")).isNotEqualTo(runs.folderOf("b"));
        assertThat(runs.folderOf("a").getFileName().toString()).startsWith("r1.child-");
    }

    @Test
    void theChildsJournalSurvivesAndItsRecordNamesItsParent() throws Exception {
        var runs = new FolderChildRuns(dir.resolve("r1"));
        runs.record("c1", "p", "Research");
        runs.record("c1", "p", "Research"); // idempotent
        runs.openJournal("c1").put("Main/s0", new RunJournal.Entry("task", "x"));

        assertThat(new FolderChildRuns(dir.resolve("r1")).openJournal("c1").get("Main/s0")).isPresent();
        assertThat(Files.readString(runs.folderOf("c1").resolve("child.json")))
                .contains("\"parent\": \"p\"")
                .contains("\"workflow\": \"Research\"");
    }
}
