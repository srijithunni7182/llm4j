package io.github.llm4j.eval.assertions;

import io.github.llm4j.model.LLMResponse;

/** Entry point for eval4j's fluent assertions on a raw {@link LLMResponse}. */
public final class LlmResponseAssertions {

    private LlmResponseAssertions() {}

    public static LlmResponseAssert assertThat(LLMResponse actual) {
        return new LlmResponseAssert(actual);
    }
}
