package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ---- F6: a child folder opens as any run (weave why, weave timeline) ----

    private Path parentWithScript() throws Exception {
        Path script = dir.resolve("flow.loom");
        Files.writeString(script, "workflow Research(topic) { set x = \"1\" }\nworkflow Main(q) { set y = \"2\" }\n");
        Path parent = dir.resolve("r1");
        new RunSpec(script.toString(), null, "Main", java.util.Map.of("q", "x"), null, null, null, null,
                dir.resolve("store").toString(), false, null)
                .write(parent);
        return parent;
    }

    @Test
    void aChildFolderCarriesARunJsonNamingTheChildsWorkflowAndTheParentsScript() throws Exception {
        Path parent = parentWithScript();
        var runs = new FolderChildRuns(parent);

        runs.record("c1", "p", "Research");
        RunSpec child = RunSpec.read(runs.folderOf("c1"));

        assertThat(child.workflow()).isEqualTo("Research");
        assertThat(child.script()).isEqualTo(RunSpec.read(parent).script());
        assertThat(child.inputs()).as("the arguments of a start are not kept in the record").isEmpty();
    }

    @Test
    void whyOpensAChildFolderLikeAnyRun() throws Exception {
        Path parent = parentWithScript();
        var runs = new FolderChildRuns(parent);
        runs.record("c1", "p", "Research");

        WhyCommandTest.Out out = WhyCommandTest.why(runs.folderOf("c1").toString());

        assertThat(out.err()).doesNotContain("not a run directory");
    }

    @Test
    void aChildFolderIsNeverRunOrResumedOnItsOwn() throws Exception {
        Path parent = parentWithScript();
        var runs = new FolderChildRuns(parent);
        runs.record("c1", "p", "Research");
        var err = new java.io.ByteArrayOutputStream();
        var env = WeaveEnv.system();

        assertThatThrownBy(() -> Runs.execute(RunSpec.read(runs.folderOf("c1")), runs.folderOf("c1"), null, env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child run")
                .hasMessageContaining("parent");
    }
}
