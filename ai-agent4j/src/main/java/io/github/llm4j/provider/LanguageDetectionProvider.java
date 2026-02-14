package io.github.llm4j.provider;

import io.github.llm4j.model.LanguageDetectionResponse;

/**
 * Service Provider Interface (SPI) for implementing Language Detection provider
 * integrations.
 */
public interface LanguageDetectionProvider {

    /**
     * Detects the language of the given text.
     *
     * @param text the text to analyze
     * @return the language detection response
     */
    LanguageDetectionResponse detectLanguage(String text);

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
