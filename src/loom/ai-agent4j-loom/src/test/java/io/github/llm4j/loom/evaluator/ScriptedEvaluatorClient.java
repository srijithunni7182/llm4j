package io.github.llm4j.loom.evaluator;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/** A fake model: scripted replies in order (the last repeats), a reply starting "THROW" throws. */
public class ScriptedEvaluatorClient implements LLMClient {

    private final List<LLMRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final Function<Integer, String> replies;

    public ScriptedEvaluatorClient(String... replies) {
        this.replies = i -> replies[Math.min(i, replies.length - 1)];
    }

    public List<LLMRequest> requests() {
        return requests;
    }

    public static String text(LLMRequest request) {
        StringBuilder sb = new StringBuilder();
        request.getMessages().forEach(m -> sb.append(m.getContent()).append('\n'));
        return sb.toString();
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        int index = requests.size();
        requests.add(request);
        String reply = replies.apply(index);
        if (reply.startsWith("THROW")) throw new IllegalStateException(reply);
        return LLMResponse.builder().content(reply).model("test").build();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.empty();
    }
}
