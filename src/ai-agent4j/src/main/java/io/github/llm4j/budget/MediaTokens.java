package io.github.llm4j.budget;

import io.github.llm4j.model.Media;

/**
 * A rough, deliberately high count of the prompt tokens a piece of {@link Media} costs, so that a
 * budget and a context window notice media instead of counting it as free. The provider's reported
 * usage replaces the estimate after the call, so a wrong guess costs at most one call's accuracy.
 *
 * <p>Providers count media in their own ways (a picture by its size, sound and video by seconds, a
 * PDF by pages), and the bytes alone do not say how big a picture is or how long a clip runs. So
 * the estimate leans on the size in bytes, with a floor and a ceiling per kind, and is meant to be
 * above what a provider charges for typical files. A real call checked it: Gemini 2.5 Flash charged
 * 268 tokens for a small picture, 268 for a one-page PDF, 77 for two seconds of WAV and 864 for a
 * two-second clip (each including about 13 tokens of question), all below these estimates. A custom
 * {@link TokenEstimator} can replace it.
 */
public final class MediaTokens {

    /** A picture: about the most a large picture costs on the providers that scale them down. */
    static final long IMAGE = 1_600;

    /** A document: one dense page is 3,000 tokens, and about one token per 40 bytes beyond that. */
    static final long DOCUMENT_FLOOR = 3_000;

    static final long DOCUMENT_BYTES_PER_TOKEN = 40;

    /**
     * A document is capped near the page limit of the providers, so one scan cannot starve a
     * budget.
     */
    static final long DOCUMENT_CEILING = 150_000;

    /**
     * Sound: 32 tokens a second, taking 8 kB a second (64 kbit/s) as the slowest realistic rate.
     */
    static final long AUDIO_FLOOR = 500;

    static final long AUDIO_BYTES_PER_TOKEN = 250;
    static final long AUDIO_CEILING = 200_000;

    /** Video: about 450 tokens a second, taking 62 kB a second (500 kbit/s) as the slowest rate. */
    static final long VIDEO_FLOOR = 2_000;

    static final long VIDEO_BYTES_PER_TOKEN = 150;
    static final long VIDEO_CEILING = 500_000;

    private MediaTokens() {}

    /** The estimated prompt tokens for one item. */
    public static long estimate(Media media) {
        long bytes = media.size();
        return switch (media.kind()) {
            case IMAGE -> IMAGE;
            case DOCUMENT -> clamp(
                    bytes / DOCUMENT_BYTES_PER_TOKEN, DOCUMENT_FLOOR, DOCUMENT_CEILING);
            case AUDIO -> clamp(bytes / AUDIO_BYTES_PER_TOKEN, AUDIO_FLOOR, AUDIO_CEILING);
            case VIDEO -> clamp(bytes / VIDEO_BYTES_PER_TOKEN, VIDEO_FLOOR, VIDEO_CEILING);
        };
    }

    private static long clamp(long value, long floor, long ceiling) {
        return Math.max(floor, Math.min(ceiling, value));
    }
}
