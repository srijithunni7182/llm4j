package io.github.llm4j.getviral.llm;

import io.github.llm4j.LLMClient;
import io.github.llm4j.getviral.studio.StudioEvents;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Decorator that reports each LLM call's latency and token usage to the studio. */
public class MeteredLLMClient implements LLMClient {

    private final LLMClient delegate;
    private final String model;
    private final StudioEvents events;

    public MeteredLLMClient(LLMClient delegate, String model, StudioEvents events) {
        this.delegate = delegate;
        this.model = model;
        this.events = events != null ? events : StudioEvents.NONE;
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        long start = System.nanoTime();
        LLMResponse response = delegate.chat(request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", model);
        data.put("ms", (System.nanoTime() - start) / 1_000_000);
        if (response.getTokenUsage() != null) {
            data.put("tokens", response.getTokenUsage().getTotalTokens());
        }
        events.emit("llm", data);
        return response;
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return delegate.chatStream(request);
    }
}
