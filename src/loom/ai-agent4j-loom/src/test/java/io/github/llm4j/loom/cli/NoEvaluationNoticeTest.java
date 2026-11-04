package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R2 of docs/specs/28-evaluation-first: a script that nothing evaluates says so once on {@code
 * weave run} and {@code weave deploy}, and {@code --quiet} and any golden case remove the line.
 */
class NoEvaluationNoticeTest {

    @TempDir Path dir;

    private final PrintStream realErr = System.err;
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private static final String NOTICE = "No evaluation: nothing has measured whether these agents are right";

    private int run(String... args) {
        err.reset();
        System.setErr(new PrintStream(err, true));
        return WeaveCLI.commandLine().execute(args);
    }

    @AfterEach
    void restore() {
        System.setErr(realErr);
    }

    private Path script() throws Exception {
        return Files.writeString(
                dir.resolve("flow.loom"),
                "agent A { model: \"ollama/llama3\"  system: \"s\" }\nworkflow Main() { note \"hi\" }\n");
    }

    private void golden(String file, String body) throws Exception {
        Path g = Files.createDirectories(dir.resolve("eval").resolve("golden"));
        Files.writeString(g.resolve(file), body);
    }

    @Test
    void runPrintsTheNoticeWhenNothingMeasuresTheAgents() throws Exception {
        assertThat(run("run", script().toString())).isZero();
        assertThat(err.toString()).contains(NOTICE).contains("weave eval --init");
    }

    @Test
    void quietLeavesItOut() throws Exception {
        assertThat(run("run", script().toString(), "--quiet")).isZero();
        assertThat(err.toString()).doesNotContain("No evaluation");
    }

    @Test
    void anyGoldenCaseRemovesIt() throws Exception {
        golden("a.yaml", "- id: a-1\n  name: first\n  input: \"x\"\n  rubric: [\"y\"]\n");
        assertThat(run("run", script().toString())).isZero();
        assertThat(err.toString()).doesNotContain("No evaluation");
    }

    @Test
    void aScaffoldWithNoCasesDoesNotCount() throws Exception {
        golden("a.yaml", "[]\n");
        assertThat(run("run", script().toString())).isZero();
        assertThat(err.toString()).contains(NOTICE);
    }

    @Test
    void aScriptWithNothingToMeasureSaysNothing() throws Exception {
        Path plain = Files.writeString(dir.resolve("plain.loom"), "workflow Main() { note \"hi\" }\n");
        assertThat(run("run", plain.toString())).isZero();
        assertThat(err.toString()).doesNotContain("No evaluation");
    }

    @Test
    void whenTheReadmeSaysItWasSkippedTheNoticeSaysThatInstead() throws Exception {
        Files.writeString(dir.resolve("README.md"), "# P\n\nEvaluation: skipped (prototype)\n");
        assertThat(run("run", script().toString())).isZero();
        assertThat(err.toString())
                .contains("Evaluation was skipped on purpose (see README).")
                .doesNotContain("No evaluation:");
    }

    @Test
    void deployPrintsTheNoticeAndTheGeneratedReadmeCarriesIt() throws Exception {
        Path out = dir.resolve("out");
        assertThat(run("deploy", script().toString(), "--out", out.toString())).isZero();
        assertThat(err.toString()).contains(NOTICE);
        assertThat(Files.readString(out.resolve("README.md")))
                .contains("## Before you rely on this")
                .contains(NOTICE);
    }

    @Test
    void deployWithCasesSaysNothingAndLeavesTheSectionOut() throws Exception {
        golden("a.yaml", "- id: a-1\n  name: first\n  input: \"x\"\n  rubric: [\"y\"]\n");
        Path out = dir.resolve("out");
        assertThat(run("deploy", script().toString(), "--out", out.toString())).isZero();
        assertThat(err.toString()).doesNotContain("No evaluation");
        assertThat(Files.readString(out.resolve("README.md"))).doesNotContain("Before you rely on this");
    }
}
