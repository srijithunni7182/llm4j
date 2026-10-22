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
 * The {@code --observe*} options on every command that runs workflows: a bad one stops before
 * anything runs, and says why.
 */
class ObserveCliTest {

    @TempDir Path dir;

    private final PrintStream realErr = System.err;
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        System.setErr(new PrintStream(err));
        return WeaveCLI.commandLine().execute(args);
    }

    @AfterEach
    void restore() {
        System.setErr(realErr);
    }

    private Path script() throws Exception {
        return Files.writeString(dir.resolve("flow.loom"), "workflow Main() { note \"hi\" }\n");
    }

    @Test
    void everyCommandThatRunsWorkflowsTakesTheOptionsAndRefusesAFabricUrlWithoutAName()
            throws Exception {
        Path store = Files.createDirectory(dir.resolve("store"));
        Path runDir = Files.createDirectory(dir.resolve("run"));
        String[][] commands = {
            {"run", script().toString()},
            {"tick", store.toString()},
            {"daemon", store.toString()},
            {"resume", runDir.toString()}
        };
        for (String[] c : commands) {
            err.reset();
            String[] args =
                    java.util.stream.Stream.concat(
                                    java.util.Arrays.stream(c),
                                    java.util.stream.Stream.of(
                                            "--observe-fabric", "https://fabric.example.com:9090"))
                            .toArray(String[]::new);
            assertThat(run(args)).as(c[0]).isEqualTo(2);
            assertThat(err.toString()).as(c[0]).contains("--observe-name");
        }
    }

    @Test
    void withoutTheAddOnOnTheClassPathTheCommandSaysSoAndRunsNothing() throws Exception {
        Path store = Files.createDirectory(dir.resolve("store"));
        assertThat(run("tick", store.toString(), "--observe=0")).isEqualTo(2);
        assertThat(err.toString()).contains("loom-inspect add-on");
    }

    @Test
    void badFabricAndOtlpOptionsAreReportedBeforeAnythingRuns() throws Exception {
        assertThat(
                        run(
                                "run",
                                script().toString(),
                                "--observe-fabric",
                                "http://fabric.example.com",
                                "--observe-name",
                                "p"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("https");
        err.reset();
        assertThat(
                        run(
                                "run",
                                script().toString(),
                                "--observe-otlp",
                                "https://c.example.com",
                                "--observe-otlp-header",
                                "bad header"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("--observe-name");
    }
}
