package io.github.llm4j.classify.fixtures;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/** A chat client that replies from a queue and records what it was asked. No network. */
public final class ScriptedChat implements LLMClient {

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<LLMRequest> requests = new ArrayList<>();

    /** The next reply is this text, finishing normally. */
    public ScriptedChat reply(String content) {
        return reply(
                LLMResponse.builder()
                        .content(content)
                        .model("scripted-1")
                        .finishReason("stop")
                        .tokenUsage(100, 20, 120)
                        .build());
    }

    public ScriptedChat reply(LLMResponse response) {
        script.add(response);
        return this;
    }

    /** The next call throws this. */
    public ScriptedChat fail(RuntimeException e) {
        script.add(e);
        return this;
    }

    public List<LLMRequest> requests() {
        return requests;
    }

    public LLMRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public synchronized LLMResponse chat(LLMRequest request) {
        requests.add(request);
        Object next = script.poll();
        if (next == null) throw new IllegalStateException("ScriptedChat has no reply left");
        if (next instanceof RuntimeException e) throw e;
        return (LLMResponse) next;
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.of(chat(request));
    }
}
