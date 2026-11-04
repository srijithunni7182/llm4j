package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.eval.NodeFingerprints;
import io.github.llm4j.loom.execution.LoomLoader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R6 of docs/specs/28: weave eval --changed runs only what changed, says why, and needs a cap. */
class ChangedEvalTest {

    @TempDir Path dir;

    private final PrintStream realOut = System.out;
    private final PrintStream realErr = System.err;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private int run(String... args) {
        out.reset();
        PrintStream both = new PrintStream(out, true);
        System.setOut(both);
        System.setErr(both);
        return WeaveCLI.commandLine().execute(args);
    }

    @AfterEach
    void restore() {
        System.setOut(realOut);
        System.setErr(realErr);
    }

    private static final String SCRIPT =
            """
            agent Writer { model: "ollama/llama3"  system: "Write well." }
            agent Editor { model: "ollama/llama3"  system: "Edit well." }
            workflow Main(q) {
                delegate "{q}" to Writer -> w
                delegate "{w}" to Editor -> e
                return e
            }
            """;

    private Path project(String source) throws Exception {
        Path g = Files.createDirectories(dir.resolve("eval/golden"));
        for (String agent : new String[] {"writer", "editor"})
            Files.writeString(g.resolve(agent + ".yaml"), "- id: " + agent + "-1\n  input: \"x\"\n  expected_output_contains: \"o\"\n");
        Files.writeString(g.resolve("main.yaml"), "- id: main-1\n  input: \"x\"\n  expected_output_contains: \"o\"\n");
        return Files.writeString(dir.resolve("main.loom"), source);
    }

    private void recordReference(Path script) throws Exception {
        NodeFingerprints.write(
                NodeFingerprints.lastRunFile(script),
                NodeFingerprints.of(new LoomLoader().load(script.toString()), script));
    }

    @Test
    void itNeedsACapBecauseItSpendsMoney() throws Exception {
        Path s = project(SCRIPT);
        assertThat(run("eval", s.toString(), "--changed")).isEqualTo(2);
        assertThat(out.toString()).contains("needs a cap");
    }

    @Test
    void withNoReferenceEverythingCountsAsChangedAndItSaysSo() throws Exception {
        Path s = project(SCRIPT);
        run("eval", s.toString(), "--changed", "--mock");
        assertThat(out.toString())
                .contains("What changed")
                .contains("Writer: no reference")
                .contains("Editor: no reference")
                .contains("there is no reference, so everything counts as changed");
    }

    @Test
    void whenNothingChangedNothingRuns() throws Exception {
        Path s = project(SCRIPT);
        recordReference(s);
        assertThat(run("eval", s.toString(), "--changed", "--mock")).isZero();
        assertThat(out.toString()).contains("nothing: no case needs to run").contains("Nothing to run");
    }

    @Test
    void onlyTheChangedAgentAndTheWorkflowThatReachesItRun() throws Exception {
        Path s = project(SCRIPT);
        recordReference(s);
        Files.writeString(s, SCRIPT.replace("Edit well.", "Edit gently."));
        run("eval", s.toString(), "--changed", "--mock");
        String text = out.toString();
        assertThat(text).contains("Editor: prompt").doesNotContain("Writer:");
        assertThat(text).contains("Editor").contains("Main");
        assertThat(text).doesNotContain("Writer ·");
    }

    @Test
    void theReferenceCanBeAFileAndABrokenFileMeansNoReference() throws Exception {
        Path s = project(SCRIPT);
        Path bad = Files.writeString(dir.resolve("old.json"), "not json");
        run("eval", s.toString(), "--changed", "--mock", "--since", bad.toString());
        assertThat(out.toString()).contains("no reference");
    }
}
