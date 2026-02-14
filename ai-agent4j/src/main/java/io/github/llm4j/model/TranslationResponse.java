package io.github.llm4j.model;

import java.util.Objects;

/**
 * Standardized response for Translation.
 */
public class TranslationResponse {

    private final String translatedText;
    private final String sourceLanguageCode; // Detected or used

    public TranslationResponse(String translatedText, String sourceLanguageCode) {
        this.translatedText = Objects.requireNonNull(translatedText, "Translated text cannot be null");
        this.sourceLanguageCode = sourceLanguageCode;
    }

    public String getTranslatedText() {
        return translatedText;
    }

    public String getSourceLanguageCode() {
        return sourceLanguageCode;
    }

    @Override
    public String toString() {
        return "TranslationResponse{" +
                "translatedText='" + translatedText + '\'' +
                ", sourceLanguageCode='" + sourceLanguageCode + '\'' +
                '}';
    }
}
