package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 19 R14 at the command: {@code weave eval --protocol nightly}. */
class EvalProtocolOptionTest {
    @TempDir Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private int eval(String protocols, String... options) throws Exception {
        Path script =
                Files.writeString(
                        dir.resolve("main.loom"),
                        "agent Helper { model: \"m\" system: \"You help.\" }\nworkflow Main(t) { delegate \"{t}\" to Helper -> r\n return r }\n");
        Files.writeString(dir.resolve("eval-protocol.yaml"), protocols);
        Path golden = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(
                golden.resolve("helper.yaml"),
                "- {id: dev-1, input: q}\n- {id: val-1, input: q, tags: [\"split:validation\"]}\n- {id: held-1, input: q, tags: [\"split:heldout\"]}\n");
        WeaveEnv env =
                new WeaveEnv(
                        m -> { throw new IllegalStateException("a mock run needs no model"); },
                        q -> "yes",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> "key");
        EvalCommand c = new EvalCommand();
        List<String> args = new ArrayList<>(List.of(script.toString()));
        args.addAll(List.of(options));
        new picocli.CommandLine(c).parseArgs(args.toArray(String[]::new));
        return EvalCommand.eval(c, env);
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private static final String PROTOCOLS =
            "protocols:\n  nightly: { split: validation, repetitions: 2 }\n  release: { release-gate: true }\n";

    @Test
    void aProtocolSetsTheSplitAndTheRepetitionsAndTheReportNamesIt() throws Exception {
        assertThat(eval(PROTOCOLS, "--mock", "--protocol", "nightly")).isZero();

        String text = said();
        assertThat(text).contains("val-1").doesNotContain("dev-1").doesNotContain("held-1");
        assertThat(text).contains("protocol = nightly");
    }

    @Test
    void whatTheCommandLineSaysBeatsTheProtocol() throws Exception {
        assertThat(eval(PROTOCOLS, "--mock", "--protocol", "nightly", "--split", "development")).isZero();

        assertThat(said()).contains("dev-1").doesNotContain("val-1");
    }

    @Test
    void aReleaseProtocolRunsTheHeldOutCases() throws Exception {
        assertThat(eval(PROTOCOLS, "--mock", "--protocol", "release")).isZero();

        assertThat(said()).contains("held-1").contains("Release gate: yes");
    }

    @Test
    void anUnknownProtocolNamesTheOnesTheFileHas() throws Exception {
        assertThat(eval(PROTOCOLS, "--mock", "--protocol", "weekly")).isEqualTo(2);

        assertThat(said()).contains("no protocol named weekly").contains("nightly, release");
    }

    @Test
    void aBrokenProtocolFileIsRefusedWithItsName() throws Exception {
        assertThat(eval("protocols:\n  nightly: { repetition: 5 }\n", "--mock", "--protocol", "nightly")).isEqualTo(2);

        assertThat(said()).contains("eval-protocol.yaml").contains("repetition");
    }
}
