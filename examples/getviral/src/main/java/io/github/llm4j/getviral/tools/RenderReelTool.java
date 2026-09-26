package io.github.llm4j.getviral.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.getviral.media.MediaAsset;
import io.github.llm4j.getviral.media.MediaLibrary;
import io.github.llm4j.getviral.media.ReelRenderer;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * Renders the Reel's beat sheet into a real vertical MP4 using the images the ArtDirector made.
 * It reads the beats straight from the workflow (the {@code reelPack} variable) so the agent only
 * decides the edit — pacing and which visuals lead — not re-types the script.
 */
public class RenderReelTool implements Tool {

    private final MediaLibrary library;
    private final Supplier<Map<String, Object>> workflow;
    private final int width;
    private final int height;
    private final int fps;

    public RenderReelTool(MediaLibrary library, Supplier<Map<String, Object>> workflow, int width, int height, int fps) {
        this.library = library;
        this.workflow = workflow;
        this.width = width;
        this.height = height;
        this.fps = fps;
    }

    @Override
    public String getName() {
        return "render_reel";
    }

    @Override
    public String getDescription() {
        return "Renders the approved Reel beat sheet into a vertical MP4 (Ken Burns motion over the generated images, "
                + "animated on-screen text, subtitles). Args: {\"pace\": \"fast|normal|slow\", "
                + "\"lead_visual\": \"reel_cover|broll\"}. Generate the images first.";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        Map<String, Object> vars = workflow.get();
        List<ReelRenderer.Beat> beats = beats(vars.get("reelPack"));
        if (beats.isEmpty()) return "Error: no Reel beat sheet found in the workflow (reelPack).";

        List<MediaAsset> visuals = new ArrayList<>(library.images().stream()
                .filter(a -> a.purpose().startsWith("broll") || a.purpose().startsWith("reel_cover")).toList());
        if ("broll".equalsIgnoreCase(PublicApiTool.arg(args, "lead_visual"))) {
            visuals.sort((a, b) -> Boolean.compare(a.purpose().startsWith("reel_cover"), b.purpose().startsWith("reel_cover")));
        } else {
            visuals.sort((a, b) -> Boolean.compare(!a.purpose().startsWith("reel_cover"), !b.purpose().startsWith("reel_cover")));
        }
        List<BufferedImage> backgrounds = new ArrayList<>();
        for (MediaAsset asset : visuals) {
            BufferedImage image = ImageIO.read(asset.cleanFile().toFile());
            if (image != null) backgrounds.add(image);
        }

        double pace = switch (PublicApiTool.arg(args, "pace").toLowerCase()) {
            case "fast" -> 0.85;
            case "slow" -> 1.15;
            default -> 1.0;
        };
        String handle = String.valueOf(vars.getOrDefault("creatorHandle", "creator"));
        Path file = library.file("reel", "mp4");
        long start = System.nanoTime();
        ReelRenderer.Result result = new ReelRenderer(width, height, fps).render(beats, backgrounds, handle, pace, file);
        long ms = (System.nanoTime() - start) / 1_000_000;
        MediaAsset asset = library.add("video", "reel", file, "GetViral Reel renderer (Java2D + jcodec H.264)", false,
                beats.size() + " beats · pace " + pace, width, height, Math.round(result.seconds() * 10) / 10.0);
        return String.format("Rendered a %.1fs %dx%d MP4 (%d beats, %d frames, %d background images) in %.1fs. Silent — "
                        + "add the soundtrack from the beat sheet in your editor.%nurl: %s",
                result.seconds(), width, height, beats.size(), result.frames(), backgrounds.size(), ms / 1000.0, asset.url());
    }

    static List<ReelRenderer.Beat> beats(Object reelPack) {
        List<ReelRenderer.Beat> beats = new ArrayList<>();
        if (reelPack instanceof Map<?, ?> reel && reel.get("beats") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> b) {
                    beats.add(new ReelRenderer.Beat(str(b.get("time")), str(b.get("shot")), str(b.get("voiceover")),
                            str(b.get("on_screen"))));
                }
            }
        }
        return beats;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
