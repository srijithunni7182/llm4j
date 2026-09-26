package io.github.llm4j.getviral.quality;

import io.github.llm4j.getviral.media.MediaInspector;
import io.github.llm4j.getviral.media.MediaInspector.Check;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic verdict on a build: is every artifact for X, Instagram and YouTube present,
 * well-formed, within platform limits, playable, original and passing the eval4j judges? Grouped by
 * the specialist who can fix it, so the Showrunner can send each problem to the right place.
 */
public final class BuildReview {

    /** Areas in the order they're reviewed; each maps to the specialist who fixes it. */
    public static final List<String> AREAS = List.of("x", "reel", "youtube", "visuals", "video");
    public static final Map<String, String> OWNER = Map.of("x", "XWriter", "reel", "ReelDirector",
            "youtube", "YouTubeProducer", "visuals", "ArtDirector", "video", "VideoEditor");

    private static final Set<String> IMAGES = Set.of("youtube_thumbnail", "reel_cover", "x_card", "broll_1", "broll_2");

    public record Area(String name, List<String> problems) {
        public boolean pass() {
            return problems.isEmpty();
        }
    }

    private BuildReview() { }

    public static Map<String, Area> review(Map<String, Object> vars, List<Check> inspection, List<QualityGate.Badge> badges) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        AREAS.forEach(a -> problems.put(a, new ArrayList<>()));

        // Completeness: every platform package exists with the parts that make it postable.
        Map<?, ?> x = map(vars.get("xPack"));
        if (list(x.get("thread")).size() < 2) problems.get("x").add("the thread is missing or has fewer than 2 posts");
        if (blank(x.get("standalone"))) problems.get("x").add("the standalone post is missing");
        Map<?, ?> reel = map(vars.get("reelPack"));
        if (list(reel.get("beats")).size() < 3) problems.get("reel").add("the beat sheet has fewer than 3 beats");
        if (blank(reel.get("caption"))) problems.get("reel").add("the caption is missing");
        if (blank(reel.get("cover_text"))) problems.get("reel").add("the cover text is missing");
        Map<?, ?> yt = map(vars.get("youtubePack"));
        if (list(yt.get("titles")).isEmpty()) problems.get("youtube").add("no title options");
        if (blank(yt.get("description"))) problems.get("youtube").add("the description is missing");
        if (list(yt.get("chapters")).isEmpty() || !String.valueOf(list(yt.get("chapters")).get(0)).strip().startsWith("0:00")) {
            problems.get("youtube").add("chapters are missing or don't start at 0:00");
        }
        if (blank(yt.get("thumbnail_text"))) problems.get("youtube").add("the thumbnail text is missing");
        Object originality = yt.get("originality");
        if (originality != null && originality.toString().startsWith("REPEAT")) {
            problems.get("youtube").add("not original enough: " + originality.toString().replaceFirst("^REPEAT — ", ""));
        }

        // Files and platform limits: the Inspector's checks.
        for (Check c : inspection) {
            if (c.status() != MediaInspector.Status.FAIL) continue;
            String area = switch (c.artifact()) {
                case "x thread" -> "x";
                case "reel caption" -> "reel";
                case "youtube package" -> "youtube";
                case "reel.mp4", "reel-preview.webm" -> "video";
                default -> IMAGES.contains(c.artifact()) ? "visuals" : null;
            };
            if (area != null) problems.get(area).add(c.artifact() + ": " + c.detail());
        }

        // Quality: the eval4j judges. Pack-wide judges (groundedness, safety) send every text area back.
        for (QualityGate.Badge b : badges) {
            if (b.passed()) continue;
            String problem = String.format("%s scored %.0f%% (needs %.0f%%): %s", b.name(), b.score() * 100,
                    b.threshold() * 100, b.reason());
            if (b.platform().equals("pack")) {
                for (String a : List.of("x", "reel", "youtube")) problems.get(a).add(problem);
            } else if (problems.containsKey(b.platform())) {
                problems.get(b.platform()).add(problem);
            }
        }

        // The Reel video is cut from the Reel plan and the images: if either changes, it must be re-rendered.
        if (!problems.get("reel").isEmpty() || !problems.get("visuals").isEmpty()) {
            problems.get("video").add("re-render after the Reel plan or images are fixed");
        }

        Map<String, Area> out = new LinkedHashMap<>();
        problems.forEach((k, v) -> out.put(k, new Area(k, List.copyOf(v))));
        return out;
    }

    public static boolean complete(Map<String, Area> review) {
        return review.values().stream().allMatch(Area::pass);
    }

    /** The review as text for the Showrunner (one line per area). */
    public static String describe(Map<String, Area> review) {
        StringBuilder out = new StringBuilder(complete(review)
                ? "BUILD COMPLETE — every artifact for X, Instagram and YouTube passes the quality gate.\n"
                : "BUILD INCOMPLETE — send each FIX to its specialist:\n");
        review.forEach((name, area) -> {
            out.append(area.pass() ? "PASS " : "FIX  ").append(name).append(" (").append(OWNER.get(name)).append(')');
            if (!area.pass()) out.append(": ").append(String.join("; ", area.problems()));
            out.append('\n');
        });
        return out.toString().strip();
    }

    private static Map<?, ?> map(Object o) {
        return o instanceof Map<?, ?> m ? m : Map.of();
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static boolean blank(Object o) {
        return o == null || o.toString().isBlank();
    }
}
