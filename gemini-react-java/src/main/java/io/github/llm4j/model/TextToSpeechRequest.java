package io.github.llm4j.model;

import io.github.llm4j.exception.LLMException;

import java.util.Objects;
import java.util.Optional;

/**
 * Standardized request for Text-to-Speech (TTS) generation.
 * This class uses the Builder pattern for construction.
 */
public class TextToSpeechRequest {

    private final String text;
    private final String targetLanguageCode;
    private final String speaker;
    private final Double pace;
    private final Integer speechSampleRate;
    private final Boolean enablePreprocessing;
    private final String model;

    private TextToSpeechRequest(Builder builder) {
        this.text = builder.text;
        this.targetLanguageCode = builder.targetLanguageCode;
        this.speaker = builder.speaker;
        this.pace = builder.pace;
        this.speechSampleRate = builder.speechSampleRate;
        this.enablePreprocessing = builder.enablePreprocessing;
        this.model = builder.model;

        validate();
    }

    private void validate() {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text cannot be null or empty");
        }
    }

    public String getText() {
        return text;
    }

    public Optional<String> getTargetLanguageCode() {
        return Optional.ofNullable(targetLanguageCode);
    }

    public Optional<String> getSpeaker() {
        return Optional.ofNullable(speaker);
    }

    public Optional<Double> getPace() {
        return Optional.ofNullable(pace);
    }

    public Optional<Integer> getSpeechSampleRate() {
        return Optional.ofNullable(speechSampleRate);
    }

    public Optional<Boolean> getEnablePreprocessing() {
        return Optional.ofNullable(enablePreprocessing);
    }

    public Optional<String> getModel() {
        return Optional.ofNullable(model);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        TextToSpeechRequest that = (TextToSpeechRequest) o;
        return Objects.equals(text, that.text) &&
                Objects.equals(targetLanguageCode, that.targetLanguageCode) &&
                Objects.equals(speaker, that.speaker) &&
                Objects.equals(pace, that.pace) &&
                Objects.equals(speechSampleRate, that.speechSampleRate) &&
                Objects.equals(enablePreprocessing, that.enablePreprocessing) &&
                Objects.equals(model, that.model);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, targetLanguageCode, speaker, pace, speechSampleRate, enablePreprocessing, model);
    }

    @Override
    public String toString() {
        return "TextToSpeechRequest{" +
                "text='" + text + '\'' +
                ", targetLanguageCode='" + targetLanguageCode + '\'' +
                ", speaker='" + speaker + '\'' +
                ", pace=" + pace +
                ", speechSampleRate=" + speechSampleRate +
                ", enablePreprocessing=" + enablePreprocessing +
                ", model='" + model + '\'' +
                '}';
    }

    public static class Builder {
        private String text;
        private String targetLanguageCode;
        private String speaker;
        private Double pace;
        private Integer speechSampleRate;
        private Boolean enablePreprocessing;
        private String model;

        private Builder() {
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder targetLanguageCode(String targetLanguageCode) {
            this.targetLanguageCode = targetLanguageCode;
            return this;
        }

        public Builder speaker(String speaker) {
            this.speaker = speaker;
            return this;
        }

        public Builder pace(Double pace) {
            this.pace = pace;
            return this;
        }

        public Builder speechSampleRate(Integer speechSampleRate) {
            this.speechSampleRate = speechSampleRate;
            return this;
        }

        public Builder enablePreprocessing(Boolean enablePreprocessing) {
            this.enablePreprocessing = enablePreprocessing;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public TextToSpeechRequest build() {
            return new TextToSpeechRequest(this);
        }
    }
}
