package io.github.llm4j.media;

import java.io.File;

/** Interface for playing audio. */
public interface AudioPlayer {

    /**
     * Plays audio from byte array.
     *
     * @param audioData The audio data (typically WAV).
     */
    void play(byte[] audioData);

    /**
     * Plays audio from byte array within a specific session context.
     *
     * @param audioData The audio data.
     * @param sessionId The session ID for caching context.
     */
    default void play(byte[] audioData, String sessionId) {
        play(audioData); // Default to simple play if not overridden
    }

    /**
     * Plays audio from a file.
     *
     * @param audioFile The audio file.
     */
    void play(File audioFile);
}
