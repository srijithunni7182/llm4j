package io.github.llm4j.getviral.media;

import java.awt.image.BufferedImage;
import java.util.Optional;

/** A source of pictures. Implementations return empty when they can't serve a request. */
public interface ImageGenerator {

    /** Label shown in the studio, e.g. "Gemini · gemini-2.5-flash-image". */
    String name();

    /** Whether the output is AI-generated (vs a local design render). */
    boolean ai();

    Optional<BufferedImage> generate(ImageRequest request);
}
