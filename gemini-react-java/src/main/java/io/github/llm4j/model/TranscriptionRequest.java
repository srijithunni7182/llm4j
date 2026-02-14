package io.github.llm4j.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Standardized request for Speech-to-Text (STT) transcription.
 */
public class TranscriptionRequest {

    // Optional prompts, language hints, etc. can be added here.
    // For Sarvam, we mainly need the file, but we might want prompt or language
    // code if known.
    private final String languageCode;
    private final String prompt;
    private final Boolean withTimestamps;
    private final Boolean translateToEnglish;

    private TranscriptionRequest(Builder builder) {
        this.languageCode = builder.languageCode;
        this.prompt = builder.prompt;
        this.withTimestamps = builder.withTimestamps;
        this.translateToEnglish = builder.translateToEnglish;
    }

    public Optional<String> getLanguageCode() {
        return Optional.ofNullable(languageCode);
    }

    public Optional<String> getPrompt() {
        return Optional.ofNullable(prompt);
    }

    public Optional<Boolean> getWithTimestamps() {
        return Optional.ofNullable(withTimestamps);
    }

    public Optional<Boolean> getTranslateToEnglish() {
        return Optional.ofNullable(translateToEnglish);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String languageCode;
        private String prompt;
        private Boolean withTimestamps;
        private Boolean translateToEnglish;

        private Builder() {
        }

        public Builder languageCode(String languageCode) {
            this.languageCode = languageCode;
            return this;
        }

        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder withTimestamps(Boolean withTimestamps) {
            this.withTimestamps = withTimestamps;
            return this;
        }

        public Builder translateToEnglish(Boolean translateToEnglish) {
            this.translateToEnglish = translateToEnglish;
            return this;
        }

        public TranscriptionRequest build() {
            return new TranscriptionRequest(this);
        }
    }
}
