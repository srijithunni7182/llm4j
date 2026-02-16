package io.github.llm4j.provider;

import io.github.llm4j.provider.LLMProvider;

public interface DescribableProvider extends LLMProvider {

    String[] listModels();

    String getFirstAvailableModel();
}
