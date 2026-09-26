package io.github.llm4j.getviral;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.getviral.engine.CastingHistory;
import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.engine.PromptBook;
import io.github.llm4j.getviral.studio.StudioRun;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same food creator makes two packs in a row. The demo model, like a real one, drifts back to its
 * favourite lens and look — the originality gate must catch it and force a genuinely new casting,
 * and the ArtDirector and YouTubeProducer must come out different from last time.
 */
class OriginalityEvalTest {

    @TempDir
    static Path dataDir;

    static GetViralEngine engine;
    static GetViralEngine.Outcome first;
    static GetViralEngine.Outcome second;
    static StudioRun secondRun;

    @BeforeAll
    static void twoPacks() {
        engine = GetViralTestSupport.engine(dataDir);
        GetViralEngine.Brief pasta = new GetViralEngine.Brief("the perfect weeknight pasta", "chef.ana", "food", "warm and witty", "US", List.of());
        StudioRun run1 = GetViralTestSupport.newRun(pasta);
        run1.autopilot(GetViralTestSupport.creator(0, null, false));
        first = engine.run(run1, pasta);

        GetViralEngine.Brief potatoes = new GetViralEngine.Brief("crispy roast potatoes", "chef.ana", "food", "warm and witty", "US", List.of());
        secondRun = GetViralTestSupport.newRun(potatoes);
        secondRun.autopilot(GetViralTestSupport.creator(0, null, false));
        second = engine.run(secondRun, potatoes);
        assertThat(first.status()).isEqualTo(StudioRun.Status.DONE);
        assertThat(second.status()).isEqualTo(StudioRun.Status.DONE);
    }

    @Test
    void everyCastingIsPersistedForTheCreator() {
        List<CastingHistory.PastCasting> history = engine.castingHistory().recent("chef.ana", 10);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).idea()).isEqualTo("crispy roast potatoes");
        assertThat(history).allSatisfy(c -> {
            assertThat(c.lens()).isNotBlank();
            assertThat(c.youtubeTitles()).isNotEmpty();
            assertThat(c.prompts()).containsKey("ArtDirector");
        });
    }

    @Test
    void theGateCatchesTheRepeatAndForcesAnOriginalRecast() {
        List<Map<String, Object>> checks = secondRun.events().stream().filter(e -> "originality".equals(e.get("type")))
                .map(e -> (Map<String, Object>) e.get("data")).filter(d -> "casting".equals(d.get("stage"))).toList();
        assertThat(checks).hasSize(2);
        assertThat(checks.get(0).get("novelty")).isEqualTo("REPEAT");
        assertThat(checks.get(0).get("feedback").toString()).contains("the perfect weeknight pasta").contains("same lens");
        assertThat(checks.get(1).get("novelty")).isEqualTo("FRESH");

        List<PromptBook.Version> showrunnerPrompts = second.prompts().all().get("ArtDirector");
        assertThat(showrunnerPrompts).extracting(PromptBook.Version::reason).contains("Re-cast for originality");
    }

    @Test
    void lensStyleAndYouTubePackagingAllChangeFromLastTime() {
        List<CastingHistory.PastCasting> history = engine.castingHistory().recent("chef.ana", 10);
        CastingHistory.PastCasting now = history.get(0), before = history.get(1);
        assertThat(now.lens()).isNotEqualTo(before.lens());
        assertThat(now.artStyle()).isNotEqualTo(before.artStyle());
        assertThat(now.youtubeTitles()).doesNotContainAnyElementsOf(before.youtubeTitles());
        assertThat(String.join(" ", now.youtubeTitles()).toLowerCase()).doesNotContain("nobody tells you");
        assertThat(now.thumbnailConcept()).isNotEqualTo(before.thumbnailConcept());
        // The YouTube package that shipped passed its originality check (after a revision if needed).
        List<Map<String, Object>> yt = secondRun.events().stream().filter(e -> "originality".equals(e.get("type")))
                .map(e -> (Map<String, Object>) e.get("data")).filter(d -> "youtube".equals(d.get("stage"))).toList();
        assertThat(yt).isNotEmpty();
        assertThat(yt.get(yt.size() - 1).get("novelty")).as(String.valueOf(yt.get(yt.size() - 1).get("reasons"))).isEqualTo("FRESH");
    }

    @Test
    void theArtDirectorWorksInTheNewStyle() {
        Map<?, ?> visuals = (Map<?, ?>) second.pack().get("visuals");
        CastingHistory.PastCasting now = engine.castingHistory().recent("chef.ana", 1).get(0);
        assertThat(String.valueOf(visuals.get("style"))).isEqualTo(now.visualStyle());
    }
}
