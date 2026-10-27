package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.ServiceUnavailableException;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R14, R38 of spec 11: the wait between a failed attempt and the next goes through the executor's
 * sleeper, so a test can make it virtual, and by default it really waits as it always did.
 */
class BackoffSleeperTest {

    @TempDir Path dir;

    static final String SCRIPT =
            "agent A { model: \"m\"  system: \"s\" }\n"
                    + "workflow Main() { delegate \"go\" to A -> r retry 1 backoff 120ms\n note \"{r}\" }\n";

    /** A model that fails on its first call and answers on its second. */
    static LLMClientFactory flaky() {
        AtomicInteger calls = new AtomicInteger();
        return model ->
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        if (calls.incrementAndGet() == 1)
                            throw new ServiceUnavailableException("t", "down");
                        return LLMResponse.builder()
                                .content("{\"thought\": \"t\", \"final_answer\": \"ok\"}")
                                .model(model)
                                .tokenUsage(1, 1, 2)
                                .build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.of(chat(request));
                    }
                };
    }

    HarnessExecutor executor(io.github.llm4j.ratelimit.Sleeper sleeper) throws Exception {
        Path file = dir.resolve("a.loom");
        Files.writeString(file, SCRIPT);
        LoomScript script = new LoomLoader().load(file.toString());
        HarnessExecutor e = new HarnessExecutor(script, new ToolRegistry(), flaky());
        if (sleeper != null) e.setSleeper(sleeper);
        e.initialize();
        return e;
    }

    @Test
    void aSleeperTakesTheBackoffAndNothingReallyWaits() throws Exception {
        List<Duration> slept = new ArrayList<>();
        HarnessExecutor e = executor(slept::add);
        long start = System.nanoTime();
        e.executeWorkflow("Main", java.util.Map.of());
        assertThat(slept).containsExactly(Duration.ofMillis(120));
        assertThat((System.nanoTime() - start) / 1_000_000).as("ms").isLessThan(2000);
        e.shutdown();
    }

    @Test
    void withoutOneTheBackoffReallyWaits() throws Exception {
        HarnessExecutor e = executor(null);
        long start = System.nanoTime();
        e.executeWorkflow("Main", java.util.Map.of());
        assertThat((System.nanoTime() - start) / 1_000_000).as("ms").isGreaterThanOrEqualTo(120);
        e.shutdown();
    }
}
