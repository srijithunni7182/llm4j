package io.github.llm4j.model;

import java.util.Locale;

/** What a piece of {@link Media} is for: the senses a model can be given a message through. */
public enum MediaKind {
    /** A picture: PNG, JPEG, GIF or WebP. */
    IMAGE,
    /** Sound: WAV, MP3 or MP4 audio. */
    AUDIO,
    /** Moving pictures: MP4 or WebM. */
    VIDEO,
    /** A document the provider reads itself: PDF only. */
    DOCUMENT;

    /** The lower-case word for messages, such as {@code image}. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
