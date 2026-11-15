package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.task.docs.IssueRefundTask;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real evaluation runs a workflow's steps for real, a payment included, and answers every
 * question to a person with yes. So before it starts it names the steps that change things, and
 * {@code --effects simulate} describes them instead of doing them.
 */
class EvalEffectsTest {

    @TempDir Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private static final String SCRIPT =
            """
            agent Helper { model: "m-help" system: "You help." }
            workflow Main(order) {
                delegate "Look at {order}" to Helper -> findings
                run IssueRefund(order = order, amount = 12) approve "Refund it?" -> receipt
                return "refunded {receipt.outcome}"
            }
            """;

    @BeforeEach
    void emptyProvider() {
        IssueRefundTask.PROVIDER.clear();
    }

    private int eval(String... options) throws Exception {
        Path script = Files.writeString(dir.resolve("main.loom"), SCRIPT);
        Path golden = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(
                golden.resolve("workflow.yaml"),
                "- {id: w-1, name: a refund, input: A-1, expected_output_contains: refunded}\n");
        LLMClientFactory models =
                model ->
                        new LLMClient() {
                            @Override
                            public LLMResponse chat(LLMRequest request) {
                                return LLMResponse.builder()
                                        .content("```json\n{\"thought\": \"t\", \"final_answer\": \"fine\"}\n```")
                                        .model(model)
                                        .tokenUsage(10, 5, 15)
                                        .build();
                            }

                            @Override
                            public Stream<LLMResponse> chatStream(LLMRequest request) {
                                return Stream.of(chat(request));
                            }
                        };
        PrintStream o = new PrintStream(out, true);
        WeaveEnv env =
                new WeaveEnv(
                        models,
                        m -> "yes",
                        o,
                        o,
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> "key");
        List<String> args = new java.util.ArrayList<>(List.of(script.toString(), "--yes", "--max-tokens", "5000"));
        args.addAll(List.of(options));
        EvalCommand c = new EvalCommand();
        new picocli.CommandLine(c).parseArgs(args.toArray(String[]::new));
        return EvalCommand.eval(c, env);
    }

    @Test
    void aRealRunNamesTheStepsThatWillChangeThingsBeforeItStarts() throws Exception {
        eval();

        assertThat(out.toString())
                .contains("These steps change things and will run for real: task IssueRefund")
                .contains("--effects simulate");
        assertThat(IssueRefundTask.PROVIDER).as("the default is unchanged: the task ran").hasSize(1);
    }

    @Test
    void effectsSimulateDescribesTheTaskAndPaysNothing() throws Exception {
        int code = eval("--effects", "simulate");

        assertThat(IssueRefundTask.PROVIDER).isEmpty();
        assertThat(code).isZero();
        assertThat(out.toString())
                .contains("described, not run: task IssueRefund")
                .doesNotContain("will run for real");
    }

    @Test
    void anEffectsValueItDoesNotKnowIsRefusedBeforeAnythingRuns() throws Exception {
        int code = eval("--effects", "pretend");

        assertThat(code).isEqualTo(2);
        assertThat(out.toString()).contains("--effects takes real or simulate");
        assertThat(IssueRefundTask.PROVIDER).isEmpty();
    }

    @Test
    void aMockRunStillDescribesEveryTaskThatChangesThings() throws Exception {
        eval("--mock");

        assertThat(IssueRefundTask.PROVIDER).isEmpty();
        assertThat(out.toString()).doesNotContain("will run for real");
    }
}
