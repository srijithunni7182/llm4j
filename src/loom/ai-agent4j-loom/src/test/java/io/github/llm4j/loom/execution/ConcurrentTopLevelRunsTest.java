package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.secret.InvokerCredentials;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** An executor runs one outermost workflow at a time; and what a worker thread sees while it does. */
class ConcurrentTopLevelRunsTest {

    private static final String SCRIPT = """
            agent Slow { model: "m" system: "You are Slow." }
            workflow Main() { delegate "go" to Slow -> x }
            """;

    private static HarnessExecutor executor(LLMClient client) {
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript(), new ToolRegistry(), x -> client);
        e.initialize();
        return e;
    }

    private static LLMClient answering(Runnable duringCall) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest r) {
                duringCall.run();
                return LLMResponse.builder().content("```json\n{\"thought\": \"t\", \"final_answer\": \"ok\"}\n```").model("m").build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest r) {
                return Stream.of(chat(r));
            }
        };
    }

    @Test
    @Tag("CC-V3.6")
    void aSecondOutermostRunOnTheSameExecutorIsRefusedAndTheFirstIsUndisturbed() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HarnessExecutor e = executor(answering(() -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> e.executeWorkflow("Main", Map.of()));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running a workflow")
                .hasMessageContaining("one HarnessExecutor for each run");
        release.countDown();
        first.get(10, TimeUnit.SECONDS);

        assertThat(e.getContext().getVariable("x")).isEqualTo("ok");
    }

    @Test
    @Tag("CC-V3.6")
    void runsOneAfterAnotherAndAfterAFailureAreFine() {
        HarnessExecutor e = executor(answering(() -> { }));

        e.executeWorkflow("Main", Map.of());
        assertThatThrownBy(() -> e.executeWorkflow("Nope", Map.of())).isInstanceOf(IllegalArgumentException.class);
        e.executeWorkflow("Main", Map.of());

        assertThat(e.getContext().getVariable("x")).isEqualTo("ok");
    }

    @Test
    @Tag("CC-V3.2")
    void aTimedCallOnAWorkerThreadSeesTheRunsInvokerAndTheWorkerIsCleanAfterwards() {
        InvokerCredentials who = InvokerCredentials.of(Map.of("token", "t"));
        AtomicReference<HarnessExecutor> holder = new AtomicReference<>();
        AtomicReference<InvokerCredentials> seenByWorker = new AtomicReference<>();
        AtomicReference<InvokerCredentials> seenByWorkerAfter = new AtomicReference<>();
        HarnessExecutor e = executor(answering(() -> {
            HarnessExecutor self = holder.get();
            try {
                Callable<InvokerCredentials> ask = self::invoker;
                seenByWorker.set(self.callWithTimeout(ask, 5_000, "probe"));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }));
        holder.set(e);

        e.executeWorkflow("Main", Map.of(), who);

        assertThat(seenByWorker.get()).isSameAs(who);
        assertThat(e.invoker()).isNull(); // the thread that ran the workflow is clean afterwards
        // the pool thread that ran the probe holds nothing for the next caller
        try {
            seenByWorkerAfter.set(e.callWithTimeout(e::invoker, 5_000, "probe"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        assertThat(seenByWorkerAfter.get()).isNull();
    }
}
