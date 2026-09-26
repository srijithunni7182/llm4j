package io.github.llm4j.getviral.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.getviral.media.ImageGenerator;
import io.github.llm4j.getviral.media.ImageRequest;
import io.github.llm4j.getviral.media.MediaAsset;
import io.github.llm4j.getviral.media.MediaLibrary;
import io.github.llm4j.getviral.media.PosterArt;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Generates an image for the content pack. Tries each provider in order (Gemini → Pollinations →
 * local design render), then typesets any overlay text on top, saves it and announces it to the studio.
 */
public class GenerateImageTool implements Tool {

    private final List<ImageGenerator> chain;
    private final MediaLibrary library;

    public GenerateImageTool(List<ImageGenerator> chain, MediaLibrary library) {
        this.chain = List.copyOf(chain);
        this.library = library;
    }

    @Override
    public String getName() {
        return "generate_image";
    }

    @Override
    public String getDescription() {
        return "Generates an image and returns its URL. Args: {\"purpose\": \"youtube_thumbnail|reel_cover|broll_1|broll_2|x_card\", "
                + "\"prompt\": \"a vivid visual description (subject, setting, lighting, lens, mood) — no text in the image\", "
                + "\"aspect_ratio\": \"16:9|9:16|1:1\", \"overlay_text\": \"optional 2-5 bold words typeset on top\"}.";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String purpose = PublicApiTool.arg(args, "purpose").isBlank() ? "image" : PublicApiTool.arg(args, "purpose");
        String prompt = PublicApiTool.arg(args, "prompt", "description");
        if (prompt.isBlank()) return "Error: 'prompt' is required — describe the image to generate.";
        ImageRequest request = new ImageRequest(purpose, prompt,
                ImageRequest.normaliseAspect(PublicApiTool.arg(args, "aspect_ratio", "aspect")),
                PublicApiTool.arg(args, "overlay_text", "text"), null);

        List<String> skipped = new ArrayList<>();
        for (ImageGenerator generator : chain) {
            Optional<BufferedImage> image = generator.generate(request);
            if (image.isEmpty()) {
                skipped.add(generator.name());
                continue;
            }
            String ext = generator.ai() ? "jpg" : "png";
            // Keep a text-free "plate" for video backgrounds, then typeset the shareable version.
            BufferedImage plate = PosterArt.cover(image.get(), request.width(), request.height());
            Path plateFile = library.file(purpose + "-plate", ext);
            ImageIO.write(plate, ext, plateFile.toFile());
            boolean hasOverlay = request.overlayText() != null && !request.overlayText().isBlank();
            BufferedImage finished = hasOverlay ? PosterArt.withOverlay(plate, request)
                    : generator.ai() ? plate : PosterArt.withCaption(plate, prompt);
            Path file = library.file(purpose, ext);
            ImageIO.write(finished, ext, file.toFile());
            MediaAsset asset = library.add("image", purpose, file, generator.name(), generator.ai(), prompt,
                    finished.getWidth(), finished.getHeight(), 0, plateFile);
            return "Generated " + purpose + " (" + asset.width() + "x" + asset.height() + ") via " + generator.name()
                    + (generator.ai() ? "" : " — a designed placeholder, not AI imagery")
                    + (skipped.isEmpty() ? "" : " [unavailable: " + String.join(", ", skipped) + "]")
                    + "\nurl: " + asset.url();
        }
        return "Could not generate " + purpose + " — no image provider available.";
    }
}
