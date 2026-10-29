package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F06: {@code weave eval} sent an empty output to three judges ("no actual output"). An output that
 * is empty is an error case with its reason; no judge call is spent on it and the evaluation exits
 * non-zero.
 */
class EvalNeverJudgesEmptyOutputTest {
    private static final String RUBRIC = "RUBRIC-LINE-POLITE";

    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final AtomicInteger judgeCalls = new AtomicInteger();

    private int eval(String script, String datasetFile, String dataset, String agentReply) throws Exception {
        Path file = Files.writeString(dir.resolve("main.loom"), script);
        Path golden = dir.resolve("eval/golden");
        Files.createDirectories(golden);
        Files.writeString(golden.resolve(datasetFile), dataset);
        WeaveEnv env =
                new WeaveEnv(
                        model ->
                                new LLMClient() {
                                    @Override
                                    public LLMResponse chat(LLMRequest request) {
                                        String all =
                                                request.getMessages().stream()
                                                        .map(m -> String.valueOf(m.getContent()))
                                                        .reduce("", (a, b) -> a + "\n" + b);
                                        boolean judging = all.contains(RUBRIC);
                                        if (judging) judgeCalls.incrementAndGet();
                                        String reply =
                                                judging
                                                        ? "{\"verdict\": \"pass\", \"reason\": \"ok\"}"
                                                        : "```json\n{\"thought\": \"t\", \"final_answer\": \""
                                                                + agentReply
                                                                + "\"}\n```";
                                        return LLMResponse.builder()
                                                .content(reply)
                                                .model(model)
                                                .tokenUsage(10, 5, 15)
                                                .build();
                                    }

                                    @Override
                                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                                        return Stream.of(chat(request));
                                    }
                                },
                        m -> "yes",
                        new PrintStream(out, true),
                        new PrintStream(new ByteArrayOutputStream(), true),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> "key");
        EvalCommand c = new EvalCommand();
        new picocli.CommandLine(c).parseArgs(file.toString(), "--yes", "--max-tokens", "100000");
        return EvalCommand.eval(c, env);
    }

    private static final String NOTHING_TO_JUDGE =
            "agent A { model: \"m-a\" system: \"You are A.\" }\n"
                    + "workflow Main(topic) { set done = \"yes\" }\n";

    private static final String ANSWERS =
            "agent A { model: \"m-a\" system: \"You are A.\" }\n"
                    + "workflow Main(topic) {\n delegate \"{topic}\" to A -> out\n return out\n}\n";

    private static final String WORKFLOW_CASE =
            "- {id: w-1, name: n, input: t, rubric: [" + RUBRIC + "]}\n";

    @Test
    void aWorkflowWithNothingToJudgeIsAnErrorAndSpendsNoJudgeCall() throws Exception {
        int code = eval(NOTHING_TO_JUDGE, "workflow.yaml", WORKFLOW_CASE, "x");

        assertThat(judgeCalls.get()).isZero();
        assertThat(code).isNotZero();
        assertThat(out.toString()).contains("the workflow produced no output");
    }

    @Test
    void anAgentThatAnswersWithNothingIsAnErrorAndSpendsNoJudgeCall() throws Exception {
        int code =
                eval(
                        ANSWERS,
                        "a.yaml",
                        "- {id: a-1, name: n, input: t, rubric: [" + RUBRIC + "]}\n",
                        "");

        assertThat(judgeCalls.get()).isZero();
        assertThat(code).isNotZero();
        assertThat(out.toString()).contains("produced no output");
    }

    @Test
    void aWorkflowWithAnAnswerIsStillJudged() throws Exception {
        eval(ANSWERS, "workflow.yaml", WORKFLOW_CASE, "polite and whole");

        assertThat(judgeCalls.get()).isGreaterThan(0);
    }
}
