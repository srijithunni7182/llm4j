package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R5 of docs/specs/28: weave eval --requirements, its html form and its sign-off. */
class RequirementsCliTest {

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

    private Path project() throws Exception {
        Path g = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(
                g.resolve("helper.yaml"),
                "- id: h-1\n  input: \"What is a workflow?\"\n  rubric: [\"answers in two sentences\"]\n");
        return Files.writeString(
                dir.resolve("main.loom"),
                "agent Helper { model: \"ollama/llama3\"  system: \"s\" }\nworkflow Main(q) { delegate \"{q}\" to Helper -> a  return a }\n");
    }

    @Test
    void itPrintsThePageAndNeedsNoKeyOrModel() throws Exception {
        assertThat(run("eval", project().toString(), "--requirements")).isZero();
        assertThat(out.toString())
                .contains("# Requirements: main.loom")
                .contains("When someone gives *What is a workflow?*")
                .contains("Not approved yet");
    }

    @Test
    void htmlIsAPageToShare() throws Exception {
        assertThat(run("eval", project().toString(), "--requirements", "--format", "html")).isZero();
        assertThat(out.toString()).contains("<!doctype html>").contains("<h1>");
    }

    @Test
    void approveRecordsWhoAndWhyAndThePageThenShowsIt() throws Exception {
        Path s = project();
        assertThat(run("eval", s.toString(), "--requirements", "--approve", "--approver", "Dana", "--why", "Read with legal")).isZero();
        assertThat(Files.readString(dir.resolve("eval/requirements-approvals.yaml"))).contains("Dana").contains("Read with legal");
        assertThat(run("eval", s.toString(), "--requirements")).isZero();
        assertThat(out.toString()).contains("Approved by Dana");
    }

    @Test
    void approveWithoutAReasonIsRefusedAndWritesNothing() throws Exception {
        Path s = project();
        assertThat(run("eval", s.toString(), "--requirements", "--approve", "--approver", "Dana")).isEqualTo(2);
        assertThat(out.toString()).contains("--why");
        assertThat(dir.resolve("eval/requirements-approvals.yaml")).doesNotExist();
    }

    @Test
    void anUnknownFormatIsRefused() throws Exception {
        assertThat(run("eval", project().toString(), "--requirements", "--format", "pdf")).isEqualTo(2);
        assertThat(out.toString()).contains("md or html");
    }

    @Test
    void approveOutsideRequirementsIsRefused() throws Exception {
        assertThat(run("eval", project().toString(), "--approve", "--why", "x")).isEqualTo(2);
        assertThat(out.toString()).contains("--requirements");
    }
}
