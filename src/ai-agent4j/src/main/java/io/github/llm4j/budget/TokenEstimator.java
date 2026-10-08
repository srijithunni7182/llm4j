package io.github.llm4j.budget;

import io.github.llm4j.model.LLMRequest;

/**
 * Estimates tokens before a call (to check and reserve a budget) and after one whose provider reported
 * no usage. Reported usage always wins over an estimate.
 */
public interface TokenEstimator {

    /** Estimated prompt tokens for a request (all its messages). */
    long prompt(LLMRequest request);

    /** Estimated tokens of a completion's text. */
    long completion(String content);
}
