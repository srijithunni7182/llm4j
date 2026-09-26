package io.github.llm4j.getviral.quality;

import io.github.llm4j.getviral.media.MediaInspector;
import io.github.llm4j.getviral.media.MediaInspector.Check;
import io.github.llm4j.getviral.media.MediaInspector.Status;
import io.github.llm4j.getviral.media.MediaLibrary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The file and text checks behind the quality gate: every artifact of the pack is verified before it
 * reaches the creator: each image decodes, has the right
 * shape and isn't blank; the Reel MP4 decodes end to end and meets Instagram's Reels spec; the WebM copy
 * is valid; and every post fits its platform's limits. Returns PASS / WARN / FAIL lines with the reason,
 * so the Showrunner can send each failure to the specialist who owns it.
 */
public class ArtifactChecks {

    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final List<String> EXPECTED_IMAGES = List.of("youtube_thumbnail", "reel_cover", "x_card", "broll_1", "broll_2");

    private final MediaLibrary library;
    private final Supplier<Map<String, Object>> workflow;
    public ArtifactChecks(MediaLibrary library, Supplier<Map<String, Object>> workflow) {
        this.library = library;
        this.workflow = workflow;
    }

    public List<Check> inspect() {
        List<Check> checks = new ArrayList<>();
        Map<String, Object> vars = workflow.get();

        // Images: the latest of each purpose (a repaired image supersedes the broken one).
        for (String purpose : EXPECTED_IMAGES) {
            library.images().stream().filter(a -> a.purpose().equals(purpose)).reduce((a, b) -> b)
                    .ifPresentOrElse(a -> checks.addAll(MediaInspector.image(a)),
                            () -> checks.add(new Check(purpose, Status.FAIL, "missing — it was never generated")));
        }

        // The Reel master and its browser copy.
        library.assets().stream().filter(a -> a.purpose().equals("reel")).reduce((a, b) -> b)
                .ifPresentOrElse(reel -> {
                    checks.addAll(MediaInspector.reel(reel.file()));
                    Path webm = reel.file().resolveSibling(reel.file().getFileName().toString().replaceFirst("\\.mp4$", "") + "-preview.webm");
                    if (Files.exists(webm)) {
                        checks.addAll(MediaInspector.webm(webm, reel.seconds()));
                    } else {
                        checks.add(new Check("reel-preview.webm", Status.WARN,
                                "no WebM copy — browsers without H.264 will show the storyboard instead"));
                    }
                }, () -> checks.add(new Check("reel.mp4", Status.FAIL, "missing — the Reel was never rendered")));

        checks.addAll(text(vars));
        return checks;
    }

    // ── platform text limits ─────────────────────────────────────────────────────────────────

    static List<Check> text(Map<String, Object> vars) {
        List<Check> checks = new ArrayList<>();
        if (vars.get("xPack") instanceof Map<?, ?> x) {
            List<String> over = new ArrayList<>();
            List<?> thread = x.get("thread") instanceof List<?> l ? l : List.of();
            for (int i = 0; i < thread.size(); i++) {
                int len = xLength(String.valueOf(thread.get(i)));
                if (len > 280) over.add("post " + (i + 1) + " is " + len);
            }
            for (String key : List.of("standalone", "reply_bait")) {
                int len = xLength(String.valueOf(x.get(key) == null ? "" : x.get(key)));
                if (len > 280) over.add(key + " is " + len);
            }
            checks.add(over.isEmpty()
                    ? new Check("x thread", Status.PASS, thread.size() + " posts, all within 280 characters")
                    : new Check("x thread", Status.FAIL, String.join("; ", over) + " characters (limit 280)"));
        }
        if (vars.get("reelPack") instanceof Map<?, ?> reel) {
            String caption = String.valueOf(reel.get("caption") == null ? "" : reel.get("caption"));
            List<?> tags = reel.get("hashtags") instanceof List<?> l ? l : List.of();
            String published = caption + "\n\n" + String.join(" ", tags.stream().map(String::valueOf).toList());
            long hashtagCount = Pattern.compile("#\\w+").matcher(published).results().count();
            long mentions = Pattern.compile("@\\w+").matcher(published).results().count();
            List<String> issues = new ArrayList<>();
            if (published.codePointCount(0, published.length()) > 2200) issues.add("caption + hashtags exceed 2,200 characters");
            if (hashtagCount > 30) issues.add(hashtagCount + " hashtags (limit 30)");
            if (mentions > 20) issues.add(mentions + " mentions (limit 20)");
            if (!(reel.get("beats") instanceof List<?> beats) || beats.isEmpty()) issues.add("no beats in the beat sheet");
            checks.add(issues.isEmpty()
                    ? new Check("reel caption", Status.PASS, published.length() + " characters, " + hashtagCount + " hashtags")
                    : new Check("reel caption", Status.FAIL, String.join("; ", issues)));
        }
        if (vars.get("youtubePack") instanceof Map<?, ?> yt) {
            List<String> issues = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            if (yt.get("titles") instanceof List<?> titles) {
                for (Object t : titles) {
                    int len = String.valueOf(t).length();
                    if (len > 100) issues.add("a title is " + len + " characters (limit 100)");
                    else if (len > 70) warnings.add("a title is " + len + " characters and will be cut off in search");
                }
            }
            String description = String.valueOf(yt.get("description") == null ? "" : yt.get("description"));
            if (description.length() > 5000) issues.add("description is " + description.length() + " characters (limit 5,000)");
            if (yt.get("tags") instanceof List<?> tags) {
                int total = tags.stream().mapToInt(t -> String.valueOf(t).length() + 1).sum();
                if (total > 500) issues.add("tags total " + total + " characters (limit 500)");
            }
            String thumb = String.valueOf(yt.get("thumbnail_text") == null ? "" : yt.get("thumbnail_text")).strip();
            if (thumb.split("\\s+").length > 5) warnings.add("thumbnail text has more than 5 words");
            if (!issues.isEmpty()) checks.add(new Check("youtube package", Status.FAIL, String.join("; ", issues)));
            else if (!warnings.isEmpty()) checks.add(new Check("youtube package", Status.WARN, String.join("; ", warnings)));
            else checks.add(new Check("youtube package", Status.PASS, "titles, description and tags within YouTube's limits"));
        }
        if (vars.get("researchDossier") instanceof Map<?, ?> research && research.get("findings") instanceof List<?> findings) {
            long unsourced = findings.stream().filter(f -> !(f instanceof Map<?, ?> m)
                    || !String.valueOf(m.get("url")).matches("https?://\\S+")).count();
            checks.add(unsourced == 0
                    ? new Check("research", Status.PASS, findings.size() + (findings.size() == 1 ? " finding" : " findings")
                            + ", each with a source link")
                    : new Check("research", Status.WARN, unsourced + " of " + findings.size() + " findings have no source link"));
        }
        return checks;
    }

    /** X's weighted length: URLs count 23, characters outside Latin/common ranges (emoji, CJK) count 2. */
    static int xLength(String text) {
        Matcher m = URL.matcher(text);
        int urls = 0;
        StringBuilder rest = new StringBuilder();
        int last = 0;
        while (m.find()) {
            urls++;
            rest.append(text, last, m.start());
            last = m.end();
        }
        rest.append(text.substring(last));
        int len = urls * 23;
        for (int cp : rest.codePoints().toArray()) len += cp <= 0x10FF || (cp >= 0x2000 && cp <= 0x200D)
                || (cp >= 0x2010 && cp <= 0x201F) || (cp >= 0x2032 && cp <= 0x2037) ? 1 : 2;
        return len;
    }
}
