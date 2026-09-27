package io.github.llm4j.budget.fixtures;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Returns scripted content in order (the last entry repeats) and reports a fixed usage — by default
 * prompt 100 + completion 50, the verification plan's "standard call". Counts calls and records every
 * request it receives. Thread-safe.
 */
public class ScriptedLLMClient implements LLMClient {

    private final List<String> contents;
    private final int prompt;
    private final int completion;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<LLMRequest> requests = Collections.synchronizedList(new ArrayList<>());

    public ScriptedLLMClient(String... contents) {
        this(100, 50, contents);
    }

    public ScriptedLLMClient(int prompt, int completion, String... contents) {
        this.contents = contents.length == 0 ? List.of("ok") : List.of(contents);
        this.prompt = prompt;
        this.completion = completion;
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        int n = calls.getAndIncrement();
        requests.add(request);
        return LLMResponse.builder()
                .content(contents.get(Math.min(n, contents.size() - 1)))
                .model("test/model")
                .tokenUsage(prompt, completion, prompt + completion)
                .build();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.of(chat(request));
    }

    public int calls() {
        return calls.get();
    }

    public List<LLMRequest> requests() {
        return requests;
    }
}
