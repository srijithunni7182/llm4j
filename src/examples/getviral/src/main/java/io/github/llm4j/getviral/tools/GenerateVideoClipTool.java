package io.github.llm4j.getviral.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.getviral.media.MediaAsset;
import io.github.llm4j.getviral.media.MediaLibrary;
import io.github.llm4j.getviral.media.VeoClient;
import java.nio.file.Path;
import java.util.Map;

/** Opt-in AI video B-roll from Google Veo (paid). Off unless GETVIRAL_VEO=true and a Gemini key is set. */
public class GenerateVideoClipTool implements Tool {

    private final VeoClient veo;
    private final MediaLibrary library;

    /** @param veo null when Veo is disabled */
    public GenerateVideoClipTool(VeoClient veo, MediaLibrary library) {
        this.veo = veo;
        this.library = library;
    }

    @Override
    public String getName() {
        return "generate_video_clip";
    }

    @Override
    public String getDescription() {
        return "Generates a short AI video clip (Google Veo) for B-roll. Slow and paid; use at most once. "
                + "Args: {\"prompt\": \"shot description with camera movement\", \"aspect_ratio\": \"9:16|16:9\"}.";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        if (veo == null) {
            return "AI video clips are turned off (Veo is a paid API — set GETVIRAL_VEO=true with GEMINI_API_KEY to enable). "
                    + "Carry on with render_reel.";
        }
        String prompt = PublicApiTool.arg(args, "prompt");
        if (prompt.isBlank()) return "Error: 'prompt' is required.";
        String aspect = "16:9".equals(PublicApiTool.arg(args, "aspect_ratio")) ? "16:9" : "9:16";
        Path file = library.file("ai-clip", "mp4");
        veo.generate(prompt, aspect, file);
        MediaAsset asset = library.add("video", "ai_clip", file, "Google Veo · " + veo.model(), true, prompt,
                aspect.equals("9:16") ? 720 : 1280, aspect.equals("9:16") ? 1280 : 720, 0);
        return "Generated an AI video clip via Google Veo.\nurl: " + asset.url();
    }
}
