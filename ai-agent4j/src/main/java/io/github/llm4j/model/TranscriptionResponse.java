package io.github.llm4j.model;

import java.util.Objects;

/**
 * Standardized response for Speech-to-Text (STT) transcription.
 */
public class TranscriptionResponse {

    private final String text;
    private final String languageCode; // Detected language

    public TranscriptionResponse(String text, String languageCode) {
        this.text = Objects.requireNonNull(text, "Transcription text cannot be null");
        this.languageCode = languageCode;
    }

    public String getText() {
        return text;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    @Override
    public String toString() {
        return "TranscriptionResponse{" +
                "text='" + text + '\'' +
                ", languageCode='" + languageCode + '\'' +
                '}';
    }
}
