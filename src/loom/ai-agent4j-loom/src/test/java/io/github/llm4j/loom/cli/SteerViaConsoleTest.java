package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomRun;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@code --steer-via console}: lines typed at the console steer the runs of the process, and answers still reach the question that asked. */
class SteerViaConsoleTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    @AfterEach
    void reset() {
        ConsoleInput.reset();
    }

    private String shown() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private void waitFor(String text) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!shown().contains(text) && System.currentTimeMillis() < end) Thread.sleep(20);
        assertThat(shown()).contains(text);
    }

    @Test
    void aTypedCommandReachesTheRunAndTheReplyIsShown() throws Exception {
        PipedOutputStream typed = new PipedOutputStream();
        ConsoleInput.start(new PipedInputStream(typed), out);
        assertThat(ConsoleInput.routing()).isTrue();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LLMClient model = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return LLMResponse.builder().content("```json\n{\"thought\": \"t\", \"final_answer\": \"done\"}\n```").model("m").build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        HarnessExecutor ex = new HarnessExecutor(new LoomParser(new Lexer("""
                agent Writer { model: "m" system: "You are Writer." tools: [calculator] steerable: true }
                workflow Main() { delegate "write" to Writer -> out_text }
                """).tokenize()).parseScript(), new ToolRegistry(), name -> model);
        ex.setJournal(RunJournal.inMemory());
        ex.initialize();
        LoomRun run = LoomRun.start(ex, "console-run", "Main", Map.of(), "test");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        typed.write("agents\nsteer Writer use the EU figures\nhello there\n".getBytes(StandardCharsets.UTF_8));
        typed.flush();
        waitFor("Sent to Writer");
        assertThat(shown()).contains("#console-run: Writer").contains("Not a command here");
        release.countDown();
        assertThat(run.await(Duration.ofSeconds(10))).isTrue();
        assertThat(ex.getJournal().all().values()).anyMatch(e -> "steer".equals(e.kind()) && String.valueOf(e.value()).contains("console"));
    }

    @Test
    void aLineTypedWhileAQuestionIsOpenIsTheAnswerNotACommand() throws Exception {
        PipedOutputStream typed = new PipedOutputStream();
        ConsoleInput.start(new PipedInputStream(typed), out);
        CompletableFuture<String> answer = CompletableFuture.supplyAsync(ConsoleInput::answer);
        Thread.sleep(200); // the question is open
        typed.write("cancel\n".getBytes(StandardCharsets.UTF_8));
        typed.flush();
        assertThat(answer.get(5, TimeUnit.SECONDS)).isEqualTo("cancel");
        assertThat(shown()).doesNotContain("Nothing is running");
    }

    @Test
    void withoutTheOptionTheConsoleIsLeftAlone() {
        assertThat(ConsoleInput.routing()).isFalse();
        SteerMixin mixin = new SteerMixin();
        assertThat(mixin.start(out, out)).isTrue();
        assertThat(ConsoleInput.routing()).isFalse();
    }

    @Test
    void anUnknownPlaceIsRefusedWithWhatThereIs() throws Exception {
        SteerMixin mixin = new SteerMixin();
        new picocli.CommandLine(mixin).parseArgs("--steer-via", "carrier-pigeon");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(mixin.start(out, new PrintStream(err, true, StandardCharsets.UTF_8))).isFalse();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("carrier-pigeon").contains("console").contains("weave daemon");
        assertThat(ConsoleInput.routing()).isFalse();
    }
}
