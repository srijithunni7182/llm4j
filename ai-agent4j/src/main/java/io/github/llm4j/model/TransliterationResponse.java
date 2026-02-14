package io.github.llm4j.model;

import java.util.Objects;

/**
 * Standardized response for Transliteration.
 */
public class TransliterationResponse {

    private final String transliteratedText;

    public TransliterationResponse(String transliteratedText) {
        this.transliteratedText = Objects.requireNonNull(transliteratedText, "Transliterated text cannot be null");
    }

    public String getTransliteratedText() {
        return transliteratedText;
    }

    @Override
    public String toString() {
        return "TransliterationResponse{" +
                "transliteratedText='" + transliteratedText + '\'' +
                '}';
    }
}
