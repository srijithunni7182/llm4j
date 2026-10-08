package io.github.llm4j.model;

import java.util.Objects;
import java.util.Optional;

/** Standardized request for Transliteration. */
public class TransliterationRequest {

    private final String text;
    private final String sourceLanguageCode;
    private final String targetLanguageCode;

    // Optional Sarvam features
    private final String outputScript; // roman, fully-native etc.
    private final String numeralsFormat; // international, native

    private TransliterationRequest(Builder builder) {
        this.text = Objects.requireNonNull(builder.text, "Text cannot be null");
        this.sourceLanguageCode = builder.sourceLanguageCode;
        this.targetLanguageCode =
                Objects.requireNonNull(
                        builder.targetLanguageCode, "Target language code cannot be null");
        this.outputScript = builder.outputScript;
        this.numeralsFormat = builder.numeralsFormat;
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

    public Optional<String> getOutputScript() {
        return Optional.ofNullable(outputScript);
    }

    public Optional<String> getNumeralsFormat() {
        return Optional.ofNullable(numeralsFormat);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String text;
        private String sourceLanguageCode;
        private String targetLanguageCode;
        private String outputScript;
        private String numeralsFormat;

        private Builder() {}

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

        public Builder outputScript(String outputScript) {
            this.outputScript = outputScript;
            return this;
        }

        public Builder numeralsFormat(String numeralsFormat) {
            this.numeralsFormat = numeralsFormat;
            return this;
        }

        public TransliterationRequest build() {
            return new TransliterationRequest(this);
        }
    }
}
