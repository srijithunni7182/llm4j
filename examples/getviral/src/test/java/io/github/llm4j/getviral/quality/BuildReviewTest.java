package io.github.llm4j.getviral.quality;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.getviral.media.MediaInspector.Check;
import io.github.llm4j.getviral.media.MediaInspector.Status;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildReviewTest {

    private static Map<String, Object> goodVars() {
        return Map.of(
                "xPack", Map.of("thread", List.of("1/ hook", "2/ body"), "standalone", "post"),
                "reelPack", Map.of("beats", List.of(Map.of(), Map.of(), Map.of()), "caption", "cap", "cover_text", "COVER"),
                "youtubePack", Map.of("titles", List.of("A title"), "description", "desc", "chapters", List.of("0:00 Intro"),
                        "thumbnail_text", "WOW"));
    }

    private static final List<QualityGate.Badge> PASSING = List.of(
            new QualityGate.Badge("Scroll-stopping X hook", "x", 0.9, 0.6, true, "ok"),
            new QualityGate.Badge("Groundedness", "pack", 0.9, 0.5, true, "ok"));

    @Test
    void aCompleteWellFormedBuildPasses() {
        Map<String, BuildReview.Area> review = BuildReview.review(goodVars(), List.of(new Check("reel.mp4", Status.PASS, "fine")), PASSING);
        assertThat(BuildReview.complete(review)).isTrue();
        assertThat(BuildReview.describe(review)).startsWith("BUILD COMPLETE");
    }

    @Test
    void eachProblemGoesToTheSpecialistWhoOwnsIt() {
        Map<String, Object> vars = new java.util.HashMap<>(goodVars());
        vars.put("youtubePack", Map.of("titles", List.of("T"), "description", "d", "chapters", List.of("Intro"), "thumbnail_text", "X",
                "originality", "REPEAT — title reuses \"nobody tells you\""));
        List<Check> inspection = List.of(
                new Check("x_card", Status.FAIL, "image is blank"),
                new Check("reel.mp4", Status.FAIL, "doesn't decode"),
                new Check("x thread", Status.FAIL, "post 2 is 300 characters (limit 280)"),
                new Check("youtube_thumbnail", Status.WARN, "small"));
        List<QualityGate.Badge> badges = List.of(new QualityGate.Badge("Reel is platform-native", "reel", 0.4, 0.6, false, "slow opening"),
                new QualityGate.Badge("Toxicity", "pack", 0.5, 0.7, false, "mean"));

        Map<String, BuildReview.Area> review = BuildReview.review(vars, inspection, badges);
        assertThat(BuildReview.complete(review)).isFalse();
        assertThat(review.get("x").problems()).anySatisfy(p -> assertThat(p).contains("300 characters")).anySatisfy(p -> assertThat(p).contains("Toxicity"));
        assertThat(review.get("reel").problems()).anySatisfy(p -> assertThat(p).contains("slow opening"));
        assertThat(review.get("youtube").problems()).anySatisfy(p -> assertThat(p).contains("0:00"))
                .anySatisfy(p -> assertThat(p).contains("not original enough"));
        assertThat(review.get("visuals").problems()).containsExactly("x_card: image is blank");
        assertThat(review.get("video").problems()).anySatisfy(p -> assertThat(p).contains("doesn't decode"))
                .anySatisfy(p -> assertThat(p).contains("re-render"));
        assertThat(BuildReview.describe(review)).contains("FIX  visuals (ArtDirector)").contains("FIX  video (VideoEditor)");
    }

    @Test
    void missingPackagesAreIncomplete() {
        Map<String, BuildReview.Area> review = BuildReview.review(Map.of(), List.of(), List.of());
        assertThat(review.get("x").pass()).isFalse();
        assertThat(review.get("reel").pass()).isFalse();
        assertThat(review.get("youtube").pass()).isFalse();
    }
}
