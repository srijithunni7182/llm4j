package io.github.llm4j.spring;

import io.github.llm4j.LLMClient;

/** Declare a bean of this type to wrap the primary {@code LLMClient} (after the budget and masking), in {@code @Order}: a cache, a tracer, a rate gate. */
@FunctionalInterface
public interface LLMClientCustomizer {
    LLMClient customize(LLMClient client);
}
