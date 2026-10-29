package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** F62 through {@code weave eval}: baselines need an approver and a reason, and datasets must match. */
class EvalBaselineCommandTest {
    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private void dataset(String yaml) throws Exception {
        Files.writeString(dir.resolve("main.loom"),
                "agent Helper { model: \"m\" system: \"You help.\" }\nworkflow Main(t) { delegate \"{t}\" to Helper -> r\n return r }\n");
        Path golden = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(golden.resolve("helper.yaml"), yaml);
    }

    private int eval(String... options) throws Exception {
        WeaveEnv env =
                new WeaveEnv(
                        model ->
                                new LLMClient() {
                                    @Override
                                    public LLMResponse chat(LLMRequest request) {
                                        return LLMResponse.builder()
                                                .content("```json\n{\"thought\": \"t\", \"final_answer\": \"The answer is 36.\"}\n```")
                                                .model(model)
                                                .tokenUsage(10, 5, 15)
                                                .build();
                                    }

                                    @Override
                                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                                        return Stream.of(chat(request));
                                    }
                                },
                        q -> "yes",
                        new PrintStream(out, true),
                        new PrintStream(err, true),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> "key");
        EvalCommand c = new EvalCommand();
        List<String> args = new ArrayList<>(List.of(dir.resolve("main.loom").toString()));
        args.addAll(List.of(options));
        new picocli.CommandLine(c).parseArgs(args.toArray(String[]::new));
        return EvalCommand.eval(c, env);
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private static final String CASES =
            "- {id: h-1, input: q, expected_output_contains: \"36\"}\n- {id: h-2, input: q, expected_output_contains: \"36\"}\n";

    @Test
    void savingABaselineWithoutAnApproverOrAReasonIsRefusedBeforeAnythingRuns() throws Exception {
        dataset(CASES);

        assertThat(eval("--yes", "--max-tokens", "100000", "--save-baseline")).isEqualTo(2);
        assertThat(said()).contains("--approver");
        out.reset();
        err.reset();
        assertThat(eval("--yes", "--max-tokens", "100000", "--save-baseline", "--approver", "ana")).isEqualTo(2);
        assertThat(said()).contains("--why");
    }

    @Test
    void aMockRunCannotBeSavedAsABaseline() throws Exception {
        dataset(CASES);

        assertThat(eval("--mock", "--save-baseline", "--approver", "ana", "--why", "x")).isEqualTo(2);
        assertThat(said()).contains("mock");
    }

    @Test
    void aFinishedRealRunIsSavedWithItsApproverAndALaterRunIsComparedWithIt() throws Exception {
        dataset(CASES);
        assertThat(eval("--yes", "--max-tokens", "100000", "--save-baseline", "--approver", "ana", "--why", "release 1")).isZero();
        assertThat(said()).contains("Baseline saved").contains("approved by ana");
        assertThat(dir.resolve("eval/baselines")).isDirectory();
        out.reset();
        err.reset();

        assertThat(eval("--yes", "--max-tokens", "100000", "--baseline")).isZero();

        assertThat(said()).contains("Current baseline").contains("approved by ana").contains("release 1")
                .contains("Previous baseline: none").contains("regressed since the current baseline: none");
    }

    @Test
    void aChangedDatasetIsRefusedForTheComparisonUnlessAReasonIsGiven() throws Exception {
        dataset(CASES);
        eval("--yes", "--max-tokens", "100000", "--save-baseline", "--approver", "ana", "--why", "release 1");
        dataset(CASES + "- {id: h-3, input: q, expected_output_contains: \"36\"}\n");
        out.reset();
        err.reset();

        assertThat(eval("--yes", "--max-tokens", "100000", "--baseline")).isEqualTo(2);
        assertThat(said()).contains("different dataset").contains("--allow-version-change");
        out.reset();
        err.reset();

        assertThat(eval("--yes", "--max-tokens", "100000", "--baseline", "--allow-version-change", "added a case")).isZero();
        assertThat(said()).contains("added a case").contains("the dataset differs");
    }

    @Test
    void theReportNamesTheDatasetVersionItWasRunOn() throws Exception {
        dataset(CASES);
        Files.writeString(dir.resolve("eval/golden/dataset.yaml"), "version: 7\ndimensions: [accuracy]\n");

        eval("--mock");

        assertThat(said()).contains("dataset version = version 7");
    }
}
