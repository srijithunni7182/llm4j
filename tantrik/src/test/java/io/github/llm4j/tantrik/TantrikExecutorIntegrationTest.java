package io.github.llm4j.tantrik;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TantrikExecutorIntegrationTest {

    @Test
    void capturesPreAndPostLifecycleOnSuccess() {
        String source = """
                agent Engineer {
                  model: "ollama/mistral"
                  system: "Implement clean code"
                }

                workflow Main() {
                  delegate "Build module from spec" to Engineer -> output
                }
                """;
        TantrikExecutor executor = new TantrikExecutor(parse(source), new ToolRegistry(), okFactory());
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());

        Object trace = executor.getContext().getVariable("_tantrik_trace");
        assertNotNull(trace);
        assertTrue(executor.getTraces().size() >= 2);
        assertEquals("Engineer", executor.getContext().getVariable("_tantrik_last_consolidated_agent"));
    }

    @Test
    void recordsErrorTraceDuringRetryThenSucceeds() {
        String source = """
                agent Engineer {
                  model: "gemini-3.5-flash"
                  system: "Implement clean code"
                }

                workflow Main() {
                  delegate "Build module from spec" to Engineer -> output retry 1 on_failure {
                    note "retry exhausted"
                  }
                }
                """;
        AtomicInteger attempts = new AtomicInteger(0);
        LLMClientFactory flakyFactory = model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                if (attempts.getAndIncrement() == 0) {
                    throw new RuntimeException("simulated first attempt failure");
                }
                return LLMResponse.builder().content("ok").build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };

        TantrikExecutor executor = new TantrikExecutor(parse(source), new ToolRegistry(), flakyFactory);
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());

        List<TantrikExecutor.TantrikTrace> traces = executor.getTraces();
        assertTrue(traces.stream().anyMatch(t -> "ERROR".equals(t.phase())));
        assertTrue(traces.stream().anyMatch(t -> "POST_TURN".equals(t.phase())));
        assertFalse(String.valueOf(executor.getContext().getVariable("output")).isBlank());
    }

    private LoomScript parse(String source) {
        Lexer lexer = new Lexer(source);
        LoomParser parser = new LoomParser(lexer.tokenize());
        return parser.parseScript();
    }

    private LLMClientFactory okFactory() {
        return model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return LLMResponse.builder()
                        .content("MOCKED_" + request.getMessages().get(request.getMessages().size() - 1).getContent())
                        .build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };
    }
}
