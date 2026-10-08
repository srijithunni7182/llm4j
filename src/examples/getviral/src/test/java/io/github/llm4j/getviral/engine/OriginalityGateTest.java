package io.github.llm4j.getviral.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.getviral.engine.CastingHistory.PastCasting;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OriginalityGateTest {

    private static final PastCasting PASTA = new PastCasting("r1", "2026-09-20T10:00:00Z", "the perfect weeknight pasta", "food",
            "Operation Pasta", "the science behind it",
            "Unpack the hidden mechanics of pasta — heat, starch chemistry and the thermodynamics of boiling water.",
            List.of("the thermodynamics of boiling", "starch chemistry", "a kitchen-lab experiment"),
            "cinematic golden-hour editorial photo, shallow depth of field, 35mm", "",
            List.of("The Truth About Pasta Nobody Tells You", "I Tried Pasta for 30 Days"), "split-screen before/after bowl", "", Map.of());

    private final OriginalityGate gate = new OriginalityGate(List.of(PASTA), "crispy roast potatoes", "food");

    @Test
    void sameLensStyleAndSignatureIdeasAreARepeat() {
        Map<String, Object> v = gate.casting(Map.of(
                "lens", "The science behind it",
                "visual_style", "cinematic golden-hour editorial photo, shallow depth of field, 35mm",
                "signature_ideas", List.of("the thermodynamics of roasting", "starch chemistry at 220C"),
                "creative_direction", "Show why potatoes crisp: starch chemistry and the thermodynamics of the oven."));
        assertThat(v.get("novelty")).isEqualTo("REPEAT");
        assertThat((List<?>) v.get("reasons")).anySatisfy(r -> assertThat(r.toString()).contains("same lens"))
                .anySatisfy(r -> assertThat(r.toString()).contains("same visual style"))
                .anySatisfy(r -> assertThat(r.toString()).contains("thermodynamics").contains("chemistry"));
        assertThat(v.get("feedback").toString()).contains("the perfect weeknight pasta");
    }

    @Test
    void theSameDirectionInNewWordsIsCaughtByMeaning() {
        Map<String, Object> v = gate.casting(Map.of(
                "lens", "a mini documentary with a narrator's voice",
                "visual_style", "risograph print, grainy two-colour overprint, pink and teal",
                "signature_ideas", List.of("narrated close-ups"),
                "creative_direction", "Unpack the hidden mechanics of pasta: heat, starch chemistry and the thermodynamics of boiling water."));
        assertThat(v.get("novelty")).isEqualTo("REPEAT");
        assertThat((List<?>) v.get("reasons")).anySatisfy(r -> assertThat(r.toString()).contains("creative direction reads like"));
    }

    @Test
    void aGenuinelyNewCastingIsFresh() {
        Map<String, Object> v = gate.casting(Map.of(
                "lens", "a street-level, on-location perspective",
                "visual_style", "gritty documentary handheld, available light, candid moment",
                "signature_ideas", List.of("a market stall owner's secret", "potatoes bought at dawn"),
                "creative_direction", "Follow a market stall owner at dawn as she picks the only potato she would ever roast."));
        assertThat(v.get("novelty")).isEqualTo("FRESH");
        assertThat((List<?>) v.get("reasons")).isEmpty();
        assertThat(((Number) v.get("closest_similarity")).doubleValue()).isLessThan(OriginalityGate.SAME_MEANING);
    }

    @Test
    void youtubeTitlesThatReuseAFormulaAreFlagged() {
        Map<String, Object> repeat = gate.youtube(Map.of("titles", List.of("The Truth About Roast Potatoes Nobody Tells You"),
                "thumbnail_concept", "a potato on a plate"));
        assertThat(repeat.get("novelty")).isEqualTo("REPEAT");
        assertThat((List<?>) repeat.get("reasons")).anySatisfy(r -> assertThat(r.toString())
                .contains("reuses the phrasing").contains("the perfect weeknight pasta"));

        Map<String, Object> fresh = gate.youtube(Map.of("titles", List.of("I Let a Market Trader Judge My Roast Potatoes"),
                "thumbnail_concept", "a stern trader holding up one golden potato at a dawn market"));
        assertThat(fresh.get("novelty")).isEqualTo("FRESH");
    }

    @Test
    void lensesAndStylesAreDealtFromWhatTheCreatorHasntUsed() {
        List<String> lenses = CreativeLenses.deal(List.of("the science behind it", "myth vs reality"), 4, 42);
        assertThat(lenses).hasSize(4).doesNotContain("the science behind it", "myth vs reality");
        List<String> styles = VisualStyles.deal(List.of(VisualStyles.ALL.get(0)), 4, 7);
        assertThat(styles).hasSize(4).doesNotContain(VisualStyles.ALL.get(0));
        assertThat(CreativeLenses.deal(List.of(), 4, 1)).isNotEqualTo(CreativeLenses.deal(List.of(), 4, 2));
    }

    @Test
    void creativityScalesOnlyTheHotRoles() {
        assertThat(GetViralExecutor.temperatureFor(1.1, 1.2)).isEqualTo(1.32, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(GetViralExecutor.temperatureFor(0.2, 1.5)).isEqualTo(0.2);
        assertThat(GetViralExecutor.temperatureFor(1.1, 2.0)).isEqualTo(1.5);
        assertThat(GetViralExecutor.temperatureFor(null, 1.2)).isNull();
    }
}
