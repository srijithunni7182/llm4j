package io.github.llm4j.loom.generate;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/** A model that answers from a list, in order, keeps what it was asked, and reports tokens. */
public final class ScriptedModel implements LLMClient {

    private final Function<Integer, String> replies;
    private final int tokensPerCall;
    public final List<String> asked = new ArrayList<>();
    public final List<String> systems = new ArrayList<>();

    public ScriptedModel(Function<Integer, String> replies, int tokensPerCall) {
        this.replies = replies;
        this.tokensPerCall = tokensPerCall;
    }

    /** Answers the given replies in order and then the last one again. */
    public static ScriptedModel of(String... replies) {
        return new ScriptedModel(n -> replies[Math.min(n, replies.length - 1)], 150);
    }

    @Override
    public synchronized LLMResponse chat(LLMRequest request) {
        int n = asked.size();
        asked.add(request.getMessages().get(request.getMessages().size() - 1).getContent());
        systems.add(request.getMessages().get(0).getContent());
        return LLMResponse.builder()
                .content(replies.apply(n))
                .model("m")
                .tokenUsage(tokensPerCall * 2 / 3, tokensPerCall / 3, tokensPerCall)
                .build();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.of(chat(request));
    }

    public int calls() {
        return asked.size();
    }
}
