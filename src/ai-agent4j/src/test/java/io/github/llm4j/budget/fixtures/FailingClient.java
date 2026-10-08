package io.github.llm4j.budget.fixtures;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Records the call, then fails like a provider outage. */
public class FailingClient implements LLMClient {

    public final AtomicInteger calls = new AtomicInteger();

    @Override
    public LLMResponse chat(LLMRequest request) {
        calls.incrementAndGet();
        throw new LLMException("provider down");
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        calls.incrementAndGet();
        throw new LLMException("provider down");
    }
}
