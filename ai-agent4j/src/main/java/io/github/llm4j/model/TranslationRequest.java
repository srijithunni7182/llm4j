package io.github.llm4j.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Standardized request for Translation.
 */
public class TranslationRequest {

    private final String text;
    private final String sourceLanguageCode;
    private final String targetLanguageCode;
    private final String speakerGender; // Optional, some APIs support it
    private final String mode; // formal, colloquial etc.

    private TranslationRequest(Builder builder) {
        this.text = Objects.requireNonNull(builder.text, "Text cannot be null");
        this.sourceLanguageCode = builder.sourceLanguageCode;
        this.targetLanguageCode = Objects.requireNonNull(builder.targetLanguageCode,
                "Target language code cannot be null");
        this.speakerGender = builder.speakerGender;
        this.mode = builder.mode;
    }

    public String getText() {
        return text;
    }

    public Optional<String> getSourceLanguageCode() {
        return Optional.ofNullable(sourceLanguageCode);
    }

    public String getTargetLanguageCode() {
        return targetLanguageCode;
    }

    public Optional<String> getSpeakerGender() {
        return Optional.ofNullable(speakerGender);
    }

    public Optional<String> getMode() {
        return Optional.ofNullable(mode);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String text;
        private String sourceLanguageCode;
        private String targetLanguageCode;
        private String speakerGender;
        private String mode;

        private Builder() {
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder sourceLanguageCode(String sourceLanguageCode) {
            this.sourceLanguageCode = sourceLanguageCode;
            return this;
        }

        public Builder targetLanguageCode(String targetLanguageCode) {
            this.targetLanguageCode = targetLanguageCode;
            return this;
        }

        public Builder speakerGender(String speakerGender) {
            this.speakerGender = speakerGender;
            return this;
        }

        public Builder mode(String mode) {
            this.mode = mode;
            return this;
        }

        public TranslationRequest build() {
            return new TranslationRequest(this);
        }
    }
}
