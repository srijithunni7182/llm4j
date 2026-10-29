package io.github.llm4j.loom.eval;

import io.github.llm4j.LLMClient;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Counts what the judge's model calls use, so the judge's cost is part of an evaluation's cost
 * instead of missing from it. Wraps the judge's client and changes nothing about the calls.
 */
public final class JudgeMeter implements LLMClient {
    private final LLMClient delegate;
    private final String model;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();

    public JudgeMeter(LLMClient delegate, String model) {
        this.delegate = delegate;
        this.model = model;
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        LLMResponse response = delegate.chat(request);
        count(response);
        return response;
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        LLMResponse[] last = new LLMResponse[1];
        return delegate.chatStream(request)
                .peek(chunk -> last[0] = chunk)
                .onClose(() -> count(last[0]));
    }

    private void count(LLMResponse response) {
        calls.incrementAndGet();
        if (response != null && response.getTokenUsage() != null) {
            promptTokens.addAndGet(response.getTokenUsage().getPromptTokens());
            completionTokens.addAndGet(response.getTokenUsage().getCompletionTokens());
        }
    }

    /** What the judge used so far; the cost is null when no price is known for its model. */
    public EvalReport.JudgeFacts facts(PriceTable prices) {
        BigDecimal cost = null;
        if (prices != null)
            cost =
                    prices.price(model)
                            .map(p -> p.cost(promptTokens.get(), completionTokens.get()))
                            .orElse(null);
        return new EvalReport.JudgeFacts(
                model, calls.get(), promptTokens.get(), completionTokens.get(), cost, false);
    }
}
