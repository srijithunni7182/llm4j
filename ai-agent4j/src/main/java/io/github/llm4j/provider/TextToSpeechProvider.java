package io.github.llm4j.provider;

import io.github.llm4j.model.TextToSpeechRequest;
import io.github.llm4j.model.TextToSpeechResponse;

/**
 * Service Provider Interface (SPI) for implementing Text-to-Speech (TTS)
 * provider integrations.
 */
public interface TextToSpeechProvider {

    /**
     * Generates speech from text.
     *
     * @param request the standardized TTS request
     * @return the standardized TTS response containing audio data
     */
    TextToSpeechResponse generateSpeech(TextToSpeechRequest request);

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
