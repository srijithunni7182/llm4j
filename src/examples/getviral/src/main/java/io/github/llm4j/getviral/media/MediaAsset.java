package io.github.llm4j.getviral.media;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** One generated image or video, stored on disk and served to the studio under {@code /media/…}. */
public record MediaAsset(String kind, String purpose, String url, Path file, String provider, boolean ai,
                         String prompt, int width, int height, double seconds, Path plate) {

    /** The text-free version of an image (what video backgrounds use), or the file itself. */
    public Path cleanFile() {
        return plate != null ? plate : file;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind);
        map.put("purpose", purpose);
        map.put("url", url);
        map.put("provider", provider);
        map.put("ai", ai);
        map.put("prompt", prompt);
        map.put("width", width);
        map.put("height", height);
        if (seconds > 0) map.put("seconds", seconds);
        return map;
    }
}
