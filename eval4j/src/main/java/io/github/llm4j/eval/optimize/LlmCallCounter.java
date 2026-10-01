package io.github.llm4j.eval.optimize;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Wraps an {@link LLMClient} and counts the calls made through it, so the optimizer's LLM-call
 * budget can include judge and agent calls:
 *
 * <pre>{@code
 * LlmCallCounter judge = LlmCallCounter.wrap(judgeClient);
 * var presets = LlmJudgePresets.using(judge);            // criteria use the counting client
 * PromptOptimizer.builder()...trackCalls(judge)...       // and the budget sees its calls
 * }</pre>
 */
public final class LlmCallCounter implements LLMClient {

    private final LLMClient delegate;
    private final AtomicLong calls = new AtomicLong();

    private LlmCallCounter(LLMClient delegate) {
        this.delegate = delegate;
    }

    public static LlmCallCounter wrap(LLMClient delegate) {
        return new LlmCallCounter(Objects.requireNonNull(delegate, "delegate cannot be null"));
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        calls.incrementAndGet();
        return delegate.chat(request);
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        calls.incrementAndGet();
        return delegate.chatStream(request);
    }

    /** Calls made through this wrapper so far. */
    public long count() {
        return calls.get();
    }
}
