package io.github.llm4j.provider;

public interface DescribableProvider extends LLMProvider {

    String[] listModels();

    String getFirstAvailableModel();
}
