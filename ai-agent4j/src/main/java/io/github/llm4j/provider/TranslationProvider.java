package io.github.llm4j.provider;

import io.github.llm4j.model.TranslationRequest;
import io.github.llm4j.model.TranslationResponse;

/**
 * Service Provider Interface (SPI) for implementing Translation provider
 * integrations.
 */
public interface TranslationProvider {

    /**
     * Translates text.
     *
     * @param request the standardized translation request
     * @return the translation response
     */
    TranslationResponse translate(TranslationRequest request);

    /**
     * Returns the name of this provider.
     *
     * @return the provider name
     */
    String getProviderName();

    /**
     * Validates that the provider is properly configured.
     *
     * @throws io.github.llm4j.exception.LLMException if the provider is not
     *                                                properly configured
     */
    void validate();
}
