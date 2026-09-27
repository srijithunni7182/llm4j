package io.github.llm4j.budget.fixtures;

import io.github.llm4j.budget.TokenEstimator;
import io.github.llm4j.model.LLMRequest;

/** Every prompt is exactly 100 tokens; completions are ceil(chars / 4 × 1.1). */
public class FixedEstimator implements TokenEstimator {

    public static final FixedEstimator INSTANCE = new FixedEstimator();

    @Override
    public long prompt(LLMRequest request) {
        return 100;
    }

    @Override
    public long completion(String content) {
        long chars = content == null ? 0 : content.length();
        return (chars * 11 + 39) / 40;
    }
}
