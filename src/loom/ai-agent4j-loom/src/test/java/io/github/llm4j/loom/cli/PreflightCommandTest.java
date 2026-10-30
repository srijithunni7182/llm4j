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

/** {@code weave preflight} through the command: it asks first, caps itself and records the result. */
class PreflightCommandTest {
    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    String answerToTheQuestion = "yes";
    int questions;

    private Path script() throws Exception {
        return Files.writeString(
                dir.resolve("main.loom"),
                "shared_prompt: \"Be brief.\"\nagent Writer { model: \"gemini-x\" system: \"You write.\" }\nworkflow Main(t) { delegate \"{t}\" to Writer -> r }\n");
    }

    private int preflight(String... options) throws Exception {
        WeaveEnv env =
                new WeaveEnv(
                        m -> { throw new IllegalStateException("only the mock may be used in this test"); },
                        q -> { questions++; return answerToTheQuestion; },
                        new PrintStream(out, true),
                        new PrintStream(err, true),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> "key");
        PreflightCommand c = new PreflightCommand();
        List<String> args = new ArrayList<>(List.of(script().toString()));
        args.addAll(List.of(options));
        new picocli.CommandLine(c).parseArgs(args.toArray(String[]::new));
        return PreflightCommand.preflight(c, env);
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aMockPreflightRunsEveryCheckWithNoQuestionAndNoModel() throws Exception {
        assertThat(preflight("--mock")).isZero();

        assertThat(questions).isZero();
        assertThat(said()).contains("C1").contains("C3").contains("C4").contains("the shared prompt reached the model");
    }

    @Test
    void itWritesTheRecordWeaveNextReadsAndItHoldsNoCanaryAndNoKey() throws Exception {
        preflight("--mock");

        Path record = dir.resolve(".loom/preflight.json");
        assertThat(record).exists();
        String json = Files.readString(record);
        assertThat(json).contains("\"script\":\"main.loom\"").contains("\"scriptHash\"").contains("\"mock\":true")
                .contains("\"build\"");
        assertThat(json).doesNotContain("CANARY-");
    }

    @Test
    void aRealPreflightListsWhatItWillSendAndAsksFirstAndANoSendsNothing() throws Exception {
        answerToTheQuestion = "no";

        assertThat(preflight()).isEqualTo(2);

        assertThat(questions).isEqualTo(1);
        assertThat(said()).contains("Writer (gemini-x)").contains("Cap:").contains("Cancelled; nothing was sent");
        assertThat(dir.resolve(".loom/preflight.json")).doesNotExist();
    }

    @Test
    void jsonFormatPrintsTheRecordInsteadOfTheTable() throws Exception {
        assertThat(preflight("--mock", "--format", "json")).isZero();

        assertThat(said()).contains("\"checks\"").doesNotContain("PASS ");
    }

    @Test
    void theOptionsAreChecked() throws Exception {
        assertThat(preflight("--mock", "--format", "xml")).isEqualTo(2);
        assertThat(said()).contains("--format takes text or json");
        out.reset();
        err.reset();
        assertThat(preflight("--mock", "--max-cost", "0.05")).isEqualTo(2);
        assertThat(said()).contains("--max-cost needs --prices");
        out.reset();
        err.reset();
        assertThat(preflight("--mock", "--max-tokens", "0")).isEqualTo(2);
    }

    @Test
    void aScriptWithNoAgentsHasNothingToProbe() throws Exception {
        Files.writeString(dir.resolve("main.loom"), "workflow Main(t) { note \"hi\" }\n");
        WeaveEnv env =
                new WeaveEnv(m -> null, q -> "yes", new PrintStream(out, true), new PrintStream(err, true),
                        Clock.systemUTC(), d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"), k -> "key");
        PreflightCommand c = new PreflightCommand();
        new picocli.CommandLine(c).parseArgs(dir.resolve("main.loom").toString(), "--mock");

        assertThat(PreflightCommand.preflight(c, env)).isEqualTo(2);
        assertThat(said()).contains("no agents");
    }
}
