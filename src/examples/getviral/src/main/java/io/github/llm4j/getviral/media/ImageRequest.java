package io.github.llm4j.getviral.media;

/**
 * What the ArtDirector asks for. {@code overlayText} is typeset by GetViral on top of the image —
 * image models are unreliable at rendering legible words, so text is always composited locally.
 */
public record ImageRequest(String purpose, String prompt, String aspectRatio, String overlayText, String accent) {

    public int width() {
        return switch (aspectRatio) {
            case "9:16" -> 720;
            case "1:1" -> 1080;
            default -> 1280;
        };
    }

    public int height() {
        return switch (aspectRatio) {
            case "9:16" -> 1280;
            case "1:1" -> 1080;
            default -> 720;
        };
    }

    public static String normaliseAspect(String raw) {
        if (raw == null) return "16:9";
        String r = raw.replace(" ", "");
        return switch (r) {
            case "9:16", "portrait", "vertical" -> "9:16";
            case "1:1", "square" -> "1:1";
            default -> "16:9";
        };
    }
}
