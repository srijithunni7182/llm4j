package io.github.llm4j.telemetry;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * Scripted models for the telemetry tests: they call a tool a few times and then answer "done", in
 * either loop.
 */
final class Scripts {
    private Scripts() {}

    /**
     * A model whose n-th reply (0-based) is given by {@code reply}; an item that is a
     * RuntimeException is thrown.
     */
    static LLMClient by(boolean nativeLoop, IntFunction<Object> reply) {
        AtomicInteger n = new AtomicInteger();
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                Object o = reply.apply(n.getAndIncrement());
                if (o instanceof RuntimeException e) throw e;
                return (LLMResponse) o;
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }

            @Override
            public boolean supportsToolCalling() {
                return nativeLoop;
            }
        };
    }

    static LLMResponse call(boolean nativeLoop, String tool, String q) {
        if (nativeLoop) {
            return LLMResponse.builder()
                    .content("go")
                    .model("m")
                    .tokenUsage(10, 5, 15)
                    .finishReason("tool_calls")
                    .toolCalls(List.of(new ToolCall("c-" + q, tool, Map.of("q", q))))
                    .build();
        }
        return LLMResponse.builder()
                .content("Thought: go\nAction: " + tool + "\nAction Input: {\"q\":\"" + q + "\"}\n")
                .model("m")
                .tokenUsage(10, 5, 15)
                .build();
    }

    static LLMResponse done(boolean nativeLoop) {
        return LLMResponse.builder()
                .content(nativeLoop ? "done" : "Thought: ok\nFinal Answer: done")
                .model("m")
                .tokenUsage(10, 5, 15)
                .finishReason("stop")
                .build();
    }

    static LLMClient oneToolStep(boolean nativeLoop, String tool, String q) {
        return by(nativeLoop, n -> n == 0 ? call(nativeLoop, tool, q) : done(nativeLoop));
    }

    static LLMClient twoIdenticalToolSteps(boolean nativeLoop, String tool, String q) {
        return by(nativeLoop, n -> n < 2 ? call(nativeLoop, tool, q) : done(nativeLoop));
    }

    static LLMClient toolSteps(boolean nativeLoop, String tool, int steps) {
        return by(nativeLoop, n -> n < steps ? call(nativeLoop, tool, "q" + n) : done(nativeLoop));
    }
}
