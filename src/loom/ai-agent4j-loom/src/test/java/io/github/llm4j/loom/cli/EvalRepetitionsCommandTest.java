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

/** {@code weave eval --repetitions N} runs every case N times and reports what that shows. */
class EvalRepetitionsCommandTest {
    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int eval(String... options) throws Exception {
        Path script =
                Files.writeString(
                        dir.resolve("main.loom"),
                        "agent Helper { model: \"m\" system: \"You help.\" }\nworkflow Main(t) { delegate \"{t}\" to Helper -> r\n return r }\n");
        Path golden = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(
                golden.resolve("helper.yaml"),
                "- {id: h-1, name: a, input: q}\n- {id: h-2, name: b, input: q}\n");
        WeaveEnv env =
                new WeaveEnv(
                        m -> { throw new IllegalStateException("a mock run needs no model"); },
                        q -> "yes",
                        new PrintStream(out, true),
                        new PrintStream(err, true),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> "key");
        EvalCommand c = new EvalCommand();
        List<String> args = new java.util.ArrayList<>(List.of(script.toString()));
        args.addAll(List.of(options));
        new picocli.CommandLine(c).parseArgs(args.toArray(String[]::new));
        return EvalCommand.eval(c, env);
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void everyCaseRunsAsManyTimesAsAskedAndTheReportSaysSo() throws Exception {
        assertThat(eval("--mock", "--repetitions", "3")).isZero();

        String text = said();
        assertThat(text).contains("(run 1 of 3)").contains("(run 3 of 3)");
        assertThat(text.split("\\(run 3 of 3\\)", -1).length - 1).isEqualTo(2); // two cases
        assertThat(text).contains("Each case ran 3 times").contains("pass@1").contains("consistency 100%");
    }

    @Test
    void theDefaultIsOneRunAndNoRunCounterIsShown() throws Exception {
        assertThat(eval("--mock")).isZero();

        assertThat(said()).doesNotContain("(run 1 of").contains("Each case ran once");
    }

    @Test
    void theNumberOfRepetitionsIsBounded() throws Exception {
        assertThat(eval("--mock", "--repetitions", "0")).isEqualTo(2);
        assertThat(said()).contains("--repetitions must be between 1 and 10");
        out.reset();
        err.reset();
        assertThat(eval("--mock", "--repetitions", "11")).isEqualTo(2);
    }

    @Test
    void theReproduceCommandRepeatsTheOption() throws Exception {
        eval("--mock", "--repetitions", "3");

        assertThat(said()).contains("weave eval main.loom --mock --repetitions 3");
    }
}
