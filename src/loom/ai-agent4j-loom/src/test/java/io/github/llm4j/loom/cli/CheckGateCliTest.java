package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R4 of docs/specs/28: the gate is in weave check and weave eval --check, and nothing skips it. */
class CheckGateCliTest {

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

    private Path script() throws Exception {
        return Files.writeString(
                dir.resolve("main.loom"),
                """
                extractor Invoice {
                    model: "ollama/llama3"
                    fields { total: number }
                }
                workflow Main(text) {
                    extract Invoice from text -> invoice
                    return invoice
                }
                """);
    }

    @Test
    void weaveCheckFailsOnAnUntestedExtractorAndNamesTheFile() throws Exception {
        assertThat(run("check", script().toString())).isEqualTo(2);
        assertThat(out.toString()).contains("Extractor `Invoice` has no golden cases").contains("invoice.yaml");
    }

    @Test
    void thereIsNoFlagThatSkipsTheGate() throws Exception {
        for (String flag : new String[] {"--lenient", "--no-env"}) {
            assertThat(run("check", script().toString(), flag)).as(flag).isEqualTo(2);
        }
        assertThat(run("check", script().toString(), "--skip-golden")).isNotZero();
    }

    @Test
    void initScaffoldsTheFileButAScaffoldNeverSatisfiesTheGate() throws Exception {
        Path s = script();
        assertThat(run("eval", s.toString(), "--init")).isZero();
        assertThat(dir.resolve("eval/golden/invoice.yaml")).isRegularFile();
        assertThat(run("check", s.toString())).isEqualTo(2);
        assertThat(out.toString()).contains("no golden cases");
    }

    @Test
    void oneCaseOfFieldsSatisfiesWeaveCheck() throws Exception {
        Path s = script();
        Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(
                dir.resolve("eval/golden/invoice.yaml"),
                "- id: i1\n  input: \"Total 5\"\n  expected_fields: { total: 5 }\n");
        assertThat(run("check", s.toString())).isZero();
        assertThat(out.toString()).doesNotContain("no golden cases");
    }

    @Test
    void weaveEvalCheckReportsTheSameProblem() throws Exception {
        Path s = script();
        assertThat(run("eval", s.toString(), "--init")).isZero();
        assertThat(run("eval", s.toString(), "--check")).isNotZero();
        assertThat(out.toString()).contains("Extractor `Invoice` has no golden cases");
    }
}
