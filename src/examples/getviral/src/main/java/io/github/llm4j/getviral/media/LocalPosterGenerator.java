package io.github.llm4j.getviral.media;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * Offline fallback: a designed poster rendered locally with Java2D (gradient mood + type). It is not
 * AI imagery and the studio labels it that way — it keeps the pipeline visual with no network.
 */
public class LocalPosterGenerator implements ImageGenerator {

    @Override
    public String name() {
        return "Local design render (Java2D)";
    }

    @Override
    public boolean ai() {
        return false;
    }

    @Override
    public Optional<BufferedImage> generate(ImageRequest request) {
        int w = request.width(), h = request.height();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = PosterArt.pen(image);
        PosterArt.moodBackground(g, w, h, request.prompt() + request.purpose());
        g.dispose();
        return Optional.of(image);
    }
}
