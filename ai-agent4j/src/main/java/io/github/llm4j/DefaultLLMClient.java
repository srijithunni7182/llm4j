package io.github.llm4j;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.LLMProvider;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Default implementation of LLMClient that delegates to a configured provider.
 */
public class DefaultLLMClient implements LLMClient {

    private final LLMProvider provider;

    public DefaultLLMClient(LLMProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.provider.validate();
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        return provider.chat(request);
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        return provider.chatStream(request);
    }

    /**
     * Returns the name of the underlying provider.
     *
     * @return provider name
     */
    public String getProviderName() {
        return provider.getProviderName();
    }
}
