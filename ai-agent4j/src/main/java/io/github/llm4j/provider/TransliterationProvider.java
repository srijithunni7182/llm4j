package io.github.llm4j.provider;

import io.github.llm4j.model.TransliterationRequest;
import io.github.llm4j.model.TransliterationResponse;

/** Service Provider Interface (SPI) for implementing Transliteration provider integrations. */
public interface TransliterationProvider {

    /**
     * Transliterates text.
     *
     * @param request the standardized transliteration request
     * @return the transliteration response
     */
    TransliterationResponse transliterate(TransliterationRequest request);

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
