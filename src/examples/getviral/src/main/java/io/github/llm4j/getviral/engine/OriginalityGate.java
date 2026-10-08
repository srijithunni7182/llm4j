package io.github.llm4j.getviral.engine;

import io.github.llm4j.engram.core.EngramEngine;
import io.github.llm4j.engram.core.models.MemoryTier;
import io.github.llm4j.engram.core.models.ScoredMemory;
import io.github.llm4j.getviral.engine.CastingHistory.PastCasting;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A symbolic check on neural creativity. Language models drift back to a favourite angle (the same
 * "science of food" lens, the same "Nobody Tells You" title) even when told not to, so GetViral
 * measures it: the new casting and YouTube package are compared with this creator's past ones by
 * exact reuse (same lens, same style, the same distinctive ideas or title phrasing) and by meaning.
 * The meaning side is Engram: the creator's past castings are loaded into an Engram memory under
 * topics ({@code direction:}, {@code style:}, {@code title:}, {@code thumbnail:}) and each new piece is
 * checked with {@link EngramEngine#nearest}. A repeat is sent back with the specific overlap named.
 */
public class OriginalityGate {

    /** Cosine similarity at or above which two directions/titles count as "the same idea, reworded". */
    static final double SAME_MEANING = 0.9;
    static final double SAME_STYLE = 0.92;
    /** Only the most recent castings are binding; older lenses may come back eventually. */
    static final int WINDOW = 6;

    private static final Set<String> GENERIC = Set.of(
            "creator", "creators", "audience", "content", "specific", "energy", "viewers", "platform", "platforms",
            "scroll", "stopping", "authentic", "people", "something", "everyday", "moment", "moments", "simple",
            "strong", "creative", "direction", "without", "through", "really", "should", "their", "about", "which",
            "makes", "making", "every", "first", "second", "video", "videos", "thread", "caption", "script", "hooks",
            "visual", "visuals", "style", "images", "image", "lighting", "colour", "colors", "palette",
            "backed", "signals", "never", "today", "radical", "specificity", "someone", "person", "relatable");

    private final List<PastCasting> history;
    private final Set<String> briefWords;
    /** A scratch Engram memory of this creator's recent work, rebuilt from the casting history each run. */
    private final EngramEngine memory;

    public OriginalityGate(List<PastCasting> history, String idea, String niche) {
        this(history, idea, niche, new EngramEngine());
    }

    OriginalityGate(List<PastCasting> history, String idea, String niche, EngramEngine memory) {
        this.history = history.subList(0, Math.min(WINDOW, history.size()));
        this.briefWords = words((idea == null ? "" : idea) + " " + (niche == null ? "" : niche));
        this.memory = memory;
        for (int i = 0; i < this.history.size(); i++) {
            PastCasting p = this.history.get(i);
            remember(p.creativeDirection(), "direction:" + i);
            remember(p.artStyle() == null || p.artStyle().isBlank() ? p.visualStyle() : p.artStyle(), "style:" + i);
            List<String> titles = p.youtubeTitles() == null ? List.of() : p.youtubeTitles();
            for (int t = 0; t < titles.size(); t++) remember(titles.get(t), "title:" + i + ":" + t);
            remember(p.thumbnailConcept(), "thumbnail:" + i);
        }
    }

    private void remember(String text, String topic) {
        if (text != null && !text.isBlank()) memory.remember(text, MemoryTier.EPISODIC, 0.1, topic);
    }

    /** The closest past piece of work under a topic, by meaning (Engram's pure-similarity recall). */
    private ScoredMemory closest(String text, String topicPrefix) {
        if (text == null || text.isBlank()) return null;
        List<ScoredMemory> nearest = memory.nearest(text, topicPrefix, 1);
        return nearest.isEmpty() ? null : nearest.get(0);
    }

    /** Which past casting a memory came from: topics are {@code kind:<castingIndex>[:<n>]}. */
    private PastCasting source(ScoredMemory m) {
        String[] parts = m.memory().getTopicKey().split(":");
        return history.get(Integer.parseInt(parts[1]));
    }

    public boolean hasHistory() {
        return !history.isEmpty();
    }

    /** Checks a Showrunner casting sheet: lens, visual style, signature ideas and direction. */
    public Map<String, Object> casting(Map<?, ?> sheet) {
        List<String> reasons = new ArrayList<>();
        String lens = str(sheet.get("lens"));
        String style = str(sheet.get("visual_style"));
        String direction = str(sheet.get("creative_direction"));
        List<String> signature = list(sheet.get("signature_ideas"));
        double closest = 0;
        String closestIdea = "";

        // Exact reuse of distinctive ideas is judged on the lens and signature ideas; whole directions are
        // compared by meaning below (a direction may mention an earlier pack without repeating it).
        Set<String> ours = distinctive(String.join(" ", signature) + " " + lens);
        for (PastCasting past : history) {
            String on = "your pack on \"" + past.idea() + "\"";
            if (!lens.isBlank() && same(lens, past.lens())) reasons.add("same lens as " + on + " (" + past.lens() + ")");
            String pastStyle = past.artStyle() == null || past.artStyle().isBlank() ? past.visualStyle() : past.artStyle();
            if (!style.isBlank() && same(style, pastStyle)) reasons.add("same visual style as " + on + " (" + pastStyle + ")");
            Set<String> theirs = distinctive(String.join(" ", past.signatureIdeas() == null ? List.of() : past.signatureIdeas())
                    + " " + str(past.lens()));
            Set<String> reused = new LinkedHashSet<>(ours);
            reused.retainAll(theirs);
            if (reused.size() >= 2) reasons.add("reuses ideas from " + on + ": " + String.join(", ", reused));
        }
        // Same meaning in new words — Engram recall by similarity alone.
        ScoredMemory nearStyle = closest(style, "style:");
        if (nearStyle != null && nearStyle.score() >= SAME_STYLE && reasons.stream().noneMatch(r -> r.startsWith("same visual style"))) {
            reasons.add(String.format("visual style is %.0f%% like your pack on \"%s\" (%s)", nearStyle.score() * 100,
                    source(nearStyle).idea(), nearStyle.memory().getContent()));
        }
        ScoredMemory nearDirection = closest(direction, "direction:");
        if (nearDirection != null) {
            closest = nearDirection.score();
            closestIdea = source(nearDirection).idea();
            if (closest >= SAME_MEANING) {
                reasons.add(String.format("creative direction reads like your pack on \"%s\" (%.0f%% similar)", closestIdea, closest * 100));
            }
        }
        return verdict(reasons, closest, closestIdea,
                "Pick a lens and a visual style from the options you haven't used, build the direction on new "
                        + "signature ideas, and rewrite every specialist's prompt around them.");
    }

    /** Checks a YouTube package's titles and thumbnail against past ones. */
    public Map<String, Object> youtube(Map<?, ?> pack) {
        List<String> reasons = new ArrayList<>();
        List<String> titles = list(pack.get("titles"));
        String thumbnail = str(pack.get("thumbnail_concept"));
        double closest = 0;
        String closestIdea = "";
        for (String title : titles) {
            // Exact phrasing reused from any past title (e.g. "nobody tells you")…
            for (PastCasting past : history) {
                for (String old : past.youtubeTitles() == null ? List.<String>of() : past.youtubeTitles()) {
                    String phrase = sharedPhrase(title, old);
                    if (!phrase.isEmpty()) {
                        reasons.add("title \"" + title + "\" reuses the phrasing \"" + phrase + "\" from your pack on \"" + past.idea() + "\"");
                    }
                }
            }
            // …or the same title in different words, by Engram similarity.
            ScoredMemory near = closest(title, "title:");
            if (near != null) {
                if (near.score() > closest) {
                    closest = near.score();
                    closestIdea = source(near).idea();
                }
                if (near.score() >= SAME_MEANING) {
                    reasons.add(String.format("title \"%s\" is %.0f%% like \"%s\" from your pack on \"%s\"", title,
                            near.score() * 100, near.memory().getContent(), source(near).idea()));
                }
            }
        }
        ScoredMemory nearThumb = closest(thumbnail, "thumbnail:");
        if (nearThumb != null && nearThumb.score() >= SAME_MEANING) {
            reasons.add("thumbnail concept repeats your pack on \"" + source(nearThumb).idea() + "\"");
        }
        return verdict(new ArrayList<>(new LinkedHashSet<>(reasons)), closest, closestIdea,
                "Invent new title formulas (not the same template with new words) and a thumbnail concept "
                        + "with a different composition and emotion.");
    }

    private Map<String, Object> verdict(List<String> reasons, double closest, String closestIdea, String advice) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("novelty", reasons.isEmpty() ? "FRESH" : "REPEAT");
        v.put("closest_similarity", Math.round(closest * 100) / 100.0);
        v.put("closest_idea", closestIdea);
        v.put("reasons", reasons);
        v.put("feedback", reasons.isEmpty() ? "" : "Too close to this creator's earlier work: " + String.join("; ", reasons)
                + ". " + advice);
        v.put("compared_with", history.size());
        return v;
    }

    // ── text helpers ─────────────────────────────────────────────────────────────────────────

    static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        String x = CreativeLenses.norm(a), y = CreativeLenses.norm(b);
        return !x.isEmpty() && !y.isEmpty() && (x.equals(y) || x.contains(y) || y.contains(x));
    }

    /** Distinctive words: long, not generic, not part of the brief itself. */
    Set<String> distinctive(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : words(text)) {
            if (w.length() >= 6 && !GENERIC.contains(w) && !briefWords.contains(w)) out.add(w);
        }
        return out;
    }

    /** A shared run of three or more words that isn't just the brief's own words. */
    String sharedPhrase(String a, String b) {
        List<String> x = List.copyOf(orderedWords(a));
        String nb = " " + String.join(" ", orderedWords(b)) + " ";
        String best = "";
        for (int n = Math.min(6, x.size()); n >= 3 && best.isEmpty(); n--) {
            for (int i = 0; i + n <= x.size(); i++) {
                List<String> gram = x.subList(i, i + n);
                if (briefWords.containsAll(gram)) continue;
                long own = gram.stream().filter(w -> !briefWords.contains(w)).count();
                String phrase = String.join(" ", gram);
                if (own >= 2 && nb.contains(" " + phrase + " ")) {
                    best = phrase;
                    break;
                }
            }
        }
        return best;
    }

    private static Set<String> words(String text) {
        return new LinkedHashSet<>(orderedWords(text));
    }

    private static List<String> orderedWords(String text) {
        List<String> out = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ").split("\\s+")) {
            if (!w.isBlank()) out.add(w.replace("'", ""));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().strip();
    }

    private static List<String> list(Object o) {
        if (o instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        return o == null || o.toString().isBlank() ? List.of() : List.of(o.toString());
    }
}
