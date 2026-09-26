package io.github.llm4j.getviral.media;

import io.github.llm4j.getviral.config.GetViralConfig;
import java.util.ArrayList;
import java.util.List;

/** Picks the image providers for a run: Gemini (with a key) → Pollinations (keyless) → local render. */
public final class MediaStudio {

    private MediaStudio() { }

    public static List<ImageGenerator> imageChain(GetViralConfig config) {
        List<ImageGenerator> chain = new ArrayList<>();
        String provider = config.imageProvider();
        boolean demoOffline = config.mode() == GetViralConfig.Mode.DEMO && config.offlineApis();
        if (provider.equals("gemini") || (provider.equals("auto") && config.geminiApiKey() != null && !demoOffline)) {
            chain.add(new GeminiImageGenerator(config.geminiApiKey(), config.imageModel()));
        }
        if (provider.equals("pollinations") || (provider.equals("auto") && !config.offlineApis())) {
            chain.add(new PollinationsImageGenerator());
        }
        chain.add(new LocalPosterGenerator());
        return chain;
    }

    public static String describe(List<ImageGenerator> chain) {
        return String.join(" → ", chain.stream().map(ImageGenerator::name).toList());
    }
}
