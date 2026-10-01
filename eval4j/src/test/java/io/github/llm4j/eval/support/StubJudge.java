package io.github.llm4j.eval.support;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Stream;

/** A scriptable, thread-safe {@link LLMClient} that records every request it receives. */
public final class StubJudge implements LLMClient {

    private final Function<LLMRequest, String> responder;
    private final List<LLMRequest> requests = new CopyOnWriteArrayList<>();

    public StubJudge(Function<LLMRequest, String> responder) {
        this.responder = responder;
    }

    /** Answers every call with the same content. */
    public static StubJudge always(String content) {
        return new StubJudge(r -> content);
    }

    /** Answers rating calls by a function of the user message. */
    public static StubJudge rating(Function<String, Integer> ratingFor) {
        return new StubJudge(r -> JudgeResponses.rating(ratingFor.apply(userMessage(r)), "stub"));
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        requests.add(request);
        return LLMResponse.builder().content(responder.apply(request)).build();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.of(chat(request));
    }

    public int callCount() {
        return requests.size();
    }

    public List<LLMRequest> requests() {
        return requests;
    }

    public static String userMessage(LLMRequest request) {
        List<Message> messages = request.getMessages();
        return messages.get(messages.size() - 1).getContent();
    }

    public static String systemMessage(LLMRequest request) {
        return request.getMessages().get(0).getContent();
    }
}
