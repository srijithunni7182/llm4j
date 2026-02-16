package io.github.llm4j.model;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Standardized response for Text-to-Speech (TTS) generation. Contains the generated audio data. */
public class TextToSpeechResponse {

    private final byte[] audioData;
    private final String contentType; // e.g., "audio/wav", "audio/mp3"

    public TextToSpeechResponse(byte[] audioData, String contentType) {
        this.audioData = Objects.requireNonNull(audioData, "Audio data cannot be null");
        this.contentType = contentType;
    }

    public byte[] getAudioData() {
        return audioData; // Consider returning a copy if immutability is strict, but usually fine
        // for
        // perf
    }

    public Optional<String> getContentType() {
        return Optional.ofNullable(contentType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TextToSpeechResponse that = (TextToSpeechResponse) o;
        return Arrays.equals(audioData, that.audioData)
                && Objects.equals(contentType, that.contentType);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(contentType);
        result = 31 * result + Arrays.hashCode(audioData);
        return result;
    }

    @Override
    public String toString() {
        return "TextToSpeechResponse{"
                + "audioDataParams="
                + audioData.length
                + " bytes"
                + ", contentType='"
                + contentType
                + '\''
                + '}';
    }
}
