package io.github.llm4j.budget.fixtures;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Returns 400 characters and reports no usage, like some local models. */
public class NoUsageClient implements LLMClient {

    public final AtomicInteger calls = new AtomicInteger();
    private final String content;

    public NoUsageClient() {
        this("x".repeat(400));
    }

    public NoUsageClient(String content) {
        this.content = content;
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        calls.incrementAndGet();
        return LLMResponse.builder().content(content).model("test/model").build();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.of(chat(request));
    }
}
