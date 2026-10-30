package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 19 R15 at the command: {@code weave eval --check} prints the mix of the dataset. */
class EvalCheckSaysWhatTheDatasetIsMadeOfTest {
    @TempDir Path dir;

    private String check(String dataset) throws Exception {
        Path script =
                Files.writeString(
                        dir.resolve("main.loom"),
                        "agent Helper { model: \"m\" system: \"You help.\" }\nworkflow Main(t) { delegate \"{t}\" to Helper -> r\n return r }\n");
        Path golden = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(golden.resolve("helper.yaml"), dataset);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "yes",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> null);
        EvalCommand c = new EvalCommand();
        new picocli.CommandLine(c).parseArgs(script.toString(), "--check");
        assertThat(EvalCommand.eval(c, env)).isZero();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theCheckNamesAMissingClassASmallSliceAndTheSourceMix() throws Exception {
        String text =
                check(
                        "- {id: a, input: q, tags: [\"difficulty:hard\", \"source:production\"]}\n"
                                + "- {id: b, input: q, tags: [\"class:unanswerable\", \"source:expert\"]}\n");

        assertThat(text).contains("ready to run");
        assertThat(text).contains("0 of 2 cases are obvious-wrong").contains("class:obvious-wrong");
        assertThat(text).contains("slice difficulty:hard has 1 cases").contains("anecdote");
        assertThat(text).contains("source mix").contains("production 50%");
    }
}
