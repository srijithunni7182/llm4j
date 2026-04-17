package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.runtime.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrencyIntegrationTest {

    @Test
    public void testConcurrencyAndObservability() throws Exception {
        String source = Files.readString(Path.of("src/test/resources/concurrency-test.loom"));
        Lexer lexer = new Lexer(source);
        LoomParser parser = new LoomParser(lexer.tokenize());
        LoomScript script = parser.parseScript();

        ToolRegistry toolRegistry = new ToolRegistry();
        AtomicInteger callCount = new AtomicInteger(0);
        
        LLMClientFactory clientFactory = (model) -> new io.github.llm4j.LLMClient() {
            @Override
            public io.github.llm4j.model.LLMResponse chat(io.github.llm4j.model.LLMRequest request) {
                callCount.getAndIncrement();
                try {
                    Thread.sleep(1000); // Simulate network latency
                } catch (InterruptedException e) {}
                return io.github.llm4j.model.LLMResponse.builder()
                        .content("Processed")
                        .build();
            }
            @Override
            public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(io.github.llm4j.model.LLMRequest request) {
                return java.util.stream.Stream.empty();
            }
        };

        HarnessExecutor executor = new HarnessExecutor(script, toolRegistry, clientFactory);
        executor.initialize();

        long start = System.currentTimeMillis();
        executor.executeWorkflow("ConcurrencyTest", Map.of("data1", "foo", "data2", "bar"));
        long end = System.currentTimeMillis();

        long duration = end - start;
        System.out.println("Execution duration: " + duration + "ms");

        // FastWorker is called twice. 
        // If parallel, duration should be ~1000ms. If sequential, ~2000ms.
        // We check if it's < 1800ms to allow for some overhead but ensure it's not 2s.
        assertTrue(duration < 1800, "Execution took too long for parallel: " + duration + "ms");
        assertEquals(2, callCount.get());

        executor.shutdown();
    }
}
