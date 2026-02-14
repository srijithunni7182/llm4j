package io.github.llm4j.provider;

import io.github.llm4j.model.TranscriptionRequest;
import io.github.llm4j.model.TranscriptionResponse;

import java.io.File;

/**
 * Service Provider Interface (SPI) for implementing Speech-to-Text (STT)
 * provider integrations.
 */
public interface SpeechToTextProvider {

    /**
     * Transcribes audio from a file.
     *
     * @param audioFile the audio file to transcribe
     * @param request   the standardized transcription request
     * @return the transcription response
     */
    TranscriptionResponse transcribe(File audioFile, TranscriptionRequest request);

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
