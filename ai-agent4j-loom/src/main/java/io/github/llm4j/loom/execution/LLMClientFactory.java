package io.github.llm4j.loom.execution;

import io.github.llm4j.LLMClient;

public interface LLMClientFactory {
    LLMClient createClient(String modelName);
}
