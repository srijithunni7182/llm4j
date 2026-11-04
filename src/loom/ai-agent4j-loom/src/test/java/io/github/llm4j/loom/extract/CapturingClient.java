package io.github.llm4j.loom.extract;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A hand-written fake {@link LLMClient}: answers with scripted replies and records every request,
 * so a test can assert exactly what reached the model (phase 24 TEST_STRATEGY section 2).
 */
class CapturingClient implements LLMClient {

    private final List<LLMRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final Function<Integer, String> replies;

    /** Replies in order; the last one repeats. */
    CapturingClient(String... replies) {
        this.replies = i -> replies[Math.min(i, replies.length - 1)];
    }

    List<LLMRequest> requests() {
        return requests;
    }

    /** Every message of the request, joined: what the model was told. */
    static String text(LLMRequest request) {
        StringBuilder sb = new StringBuilder();
        request.getMessages().forEach(m -> sb.append(m.getContent()).append('\n'));
        return sb.toString();
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        int index = requests.size();
        requests.add(request);
        return LLMResponse.builder().content(replies.apply(index)).model("test").build();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.empty();
    }
}
