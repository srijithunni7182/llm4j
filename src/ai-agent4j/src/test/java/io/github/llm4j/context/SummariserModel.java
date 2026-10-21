package io.github.llm4j.context;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The model a summarising strategy calls in tests: replies from a function of the request, and
 * records what it was sent.
 */
final class SummariserModel implements LLMClient {
    final List<LLMRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final Function<LLMRequest, Object>
            reply; // a String (the summary), an LLMResponse, or a RuntimeException to throw

    SummariserModel(Function<LLMRequest, Object> reply) {
        this.reply = reply;
    }

    static SummariserModel saying(String summary) {
        return new SummariserModel(r -> summary);
    }

    static SummariserModel failing(RuntimeException e) {
        return new SummariserModel(r -> e);
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        requests.add(request);
        Object o = reply.apply(request);
        if (o instanceof RuntimeException e) throw e;
        if (o instanceof LLMResponse r) return r;
        String text = (String) o;
        return LLMResponse.builder()
                .content(text)
                .model("summariser")
                .tokenUsage(700, 60, 760)
                .addMetadata(
                        io.github.llm4j.budget.BudgetedLLMClient.COST, new BigDecimal("0.0021"))
                .build();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.of(chat(request));
    }

    String systemPrompt() {
        return requests.get(0).getMessages().get(0).getContent();
    }

    String transcript() {
        return requests.get(0).getMessages().get(1).getContent();
    }
}
