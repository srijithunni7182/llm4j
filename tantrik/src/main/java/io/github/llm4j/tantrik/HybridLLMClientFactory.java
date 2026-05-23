package io.github.llm4j.tantrik;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.LLMClientFactory;

public class HybridLLMClientFactory implements LLMClientFactory {
    private final LLMClientFactory delegate;
    private final HybridModelRegistry modelRegistry;

    public HybridLLMClientFactory(LLMClientFactory delegate, HybridModelRegistry modelRegistry) {
        this.delegate = delegate;
        this.modelRegistry = modelRegistry;
    }

    @Override
    public LLMClient createClient(String modelName) {
        modelRegistry.resolveRoute(modelName);
        return delegate.createClient(modelName);
    }
}
