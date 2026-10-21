package io.github.llm4j.provider;

import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;

/**
 * Service Provider Interface (SPI) for backends that answer typed questions directly, such as Jev,
 * which does not chat. Wrap one with {@link
 * io.github.llm4j.classify.Classifier#using(ClassificationProvider)}: the wrapper checks every
 * result against the request, so a provider only has to translate. A chat model needs no provider
 * here; see {@link io.github.llm4j.classify.LlmClassifier}.
 */
public interface ClassificationProvider {

    /**
     * Answers every question of the request in one call.
     *
     * @param request a request that already passed its own validation
     * @return the answers
     * @throws io.github.llm4j.exception.LLMException if the call fails or the reply cannot be used
     */
    ClassifyResult classify(ClassifyRequest request);

    /**
     * Returns the name of this provider.
     *
     * @return the provider name
     */
    String getProviderName();

    /**
     * Validates that the provider is properly configured.
     *
     * @throws io.github.llm4j.exception.LLMException if the provider is not properly configured
     */
    void validate();
}
