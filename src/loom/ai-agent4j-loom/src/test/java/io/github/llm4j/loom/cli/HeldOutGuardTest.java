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

/**
 * F55 and D12 (b): a team that tunes prompts against the cases it can see cannot tell it has fitted
 * them. Cases tagged {@code split:heldout} run only at a release gate, which the run records, so a
 * held-out result cannot be looked at by accident while tuning.
 */
class HeldOutGuardTest {
    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int eval(String dataset, String... options) throws Exception {
        Path script =
                Files.writeString(
                        dir.resolve("main.loom"),
                        "agent Helper { model: \"m\" system: \"You help.\" }\nworkflow Main(t) { delegate \"{t}\" to Helper -> r\n return r }\n");
        Path golden = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(golden.resolve("helper.yaml"), dataset);
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
        List<String> args = new ArrayList<>(List.of(script.toString()));
        args.addAll(List.of(options));
        new picocli.CommandLine(c).parseArgs(args.toArray(String[]::new));
        return EvalCommand.eval(c, env);
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private static final String DATASET =
            "- {id: dev-1, input: q, tags: [\"split:development\"]}\n"
                    + "- {id: val-1, input: q, tags: [\"split:validation\"]}\n"
                    + "- {id: held-1, input: q, tags: [\"split:heldout\"]}\n"
                    + "- {id: plain-1, input: q}\n";

    @Test
    void anOrdinaryRunDoesNotRunHeldOutCasesAndSaysHowManyItLeftOut() throws Exception {
        assertThat(eval(DATASET, "--mock")).isZero();

        String text = said();
        assertThat(text).contains("dev-1").contains("val-1").contains("plain-1");
        assertThat(text).doesNotContain("held-1");
        assertThat(text).contains("1 held-out case").contains("--release-gate");
    }

    @Test
    void askingForTheHeldOutSplitWithoutAGateIsRefusedAndExplained() throws Exception {
        assertThat(eval(DATASET, "--mock", "--split", "heldout")).isEqualTo(2);

        assertThat(said()).contains("held-out").contains("--release-gate");
    }

    @Test
    void aReleaseGateRunDoesRunThemAndRecordsThatItWasOne() throws Exception {
        assertThat(eval(DATASET, "--mock", "--release-gate")).isZero();

        String text = said();
        assertThat(text).contains("held-1").contains("Release gate: yes");
        assertThat(text).doesNotContain("held-out case(s) were not run");
    }

    @Test
    void aSplitOptionRunsOnlyThatSplit() throws Exception {
        assertThat(eval(DATASET, "--mock", "--split", "validation")).isZero();

        String text = said();
        assertThat(text).contains("val-1").doesNotContain("dev-1").doesNotContain("plain-1");
    }

    @Test
    void anUnknownSplitNameIsAProblemWithTheDatasetNotASilentDevelopmentCase() throws Exception {
        int code = eval("- {id: x-1, input: q, tags: [\"split:holdout\"]}\n", "--mock");

        assertThat(code).isEqualTo(2);
        assertThat(said()).contains("split").contains("development, validation or heldout");
    }

    @Test
    void checkWarnsWhenThereAreNoHeldOutCasesAndIsQuietWhenThereAre() throws Exception {
        eval("- {id: dev-1, input: q}\n", "--check");
        assertThat(said()).contains("no held-out cases");

        out.reset();
        err.reset();
        eval(DATASET, "--check");
        assertThat(said()).doesNotContain("no held-out cases");
    }

    @Test
    void checkSaysHowManyCasesAnAssistantDraftedAndNoPersonReviewed() throws Exception {
        eval(
                "- {id: a-1, input: q, tags: [\"author:assistant\"]}\n"
                        + "- {id: a-2, input: q, tags: [\"author:assistant\", \"reviewed_by:Asha\"]}\n"
                        + "- {id: p-1, input: q}\n",
                "--check");
        assertThat(said()).contains("1 of 3 cases were drafted by an assistant");

        out.reset();
        err.reset();
        eval("- {id: p-1, input: q}\n", "--check");
        assertThat(said()).doesNotContain("drafted by an assistant");
    }

    @Test
    void aFinishedEvaluationIsRecordedSoWeaveNextCanTellMockFromReal() throws Exception {
        assertThat(eval(DATASET, "--mock")).isZero();

        var recorded = EvalRecord.read(dir).orElseThrow();
        assertThat(recorded.mode()).isEqualTo("mock");
        assertThat(recorded.datasetVersion()).isNotBlank();
        assertThat(recorded.scriptHash()).isEqualTo(PreflightCommand.hash(Files.readString(dir.resolve("main.loom"))));
    }

    @Test
    void aCheckRecordsNothingBecauseNothingWasEvaluated() throws Exception {
        eval(DATASET, "--check");

        assertThat(EvalRecord.read(dir)).isEmpty();
    }
}
