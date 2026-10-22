package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R5: a window kept at the server fills in for a process that did not know it; what a process
 * reports always wins.
 */
class WindowCatalogTest {

    private final ContextFixtures f = new ContextFixtures();

    private static WindowBook book(String text) {
        WindowBook.Parsed p = WindowBook.parse(text);
        assertThat(p.ok()).as(p.problems().toString()).isTrue();
        return p.book();
    }

    @Test
    void anAgentWithoutAWindowGetsOneFromTheCatalogByItsModel() {
        f.state.setModels(Map.of("Editor", "big-model"));
        f.state.setWindows(book("big-model = 50000"));
        f.context(1, "Editor", 40_000, null, null);
        Map<String, Object> c = f.context("Editor");
        assertThat(c).containsEntry("usable", 50_000L).containsEntry("windowSource", "catalog");
        assertThat((double) c.get("fraction")).isEqualTo(0.8);
    }

    @Test
    void aWindowTheProcessReportedWins() {
        f.state.setModels(Map.of("Editor", "big-model"));
        f.state.setWindows(book("big-model = 50000"));
        f.context(1, "Editor", 500, 1200L, 1000L);
        Map<String, Object> c = f.context("Editor");
        assertThat(c).containsEntry("usable", 1000L).containsEntry("windowSource", "process");
        assertThat((double) c.get("fraction")).isEqualTo(0.5);
    }

    @Test
    void aModelNotInTheCatalogChangesNothing() {
        f.state.setModels(Map.of("Editor", "other-model"));
        f.state.setWindows(book("big-model = 50000"));
        f.context(1, "Editor", 400, null, null);
        assertThat(f.context("Editor")).doesNotContainKeys("usable", "fraction", "windowSource");
    }

    @Test
    void anAgentWhoseModelIsUnknownChangesNothing() {
        f.state.setWindows(book("big-model = 50000"));
        f.context(1, "Editor", 400, null, null);
        assertThat(f.context("Editor")).doesNotContainKeys("usable", "fraction", "windowSource");
    }

    @Test
    void theModelIsFoundWithoutItsProviderPrefix() {
        f.state.setModels(Map.of("Editor", "openai/big-model"));
        f.state.setWindows(book("big-model = 50000"));
        f.context(1, "Editor", 25_000, null, null);
        assertThat(f.context("Editor")).containsEntry("usable", 50_000L);
    }

    @Test
    void theContextIncidentUsesTheFilledFraction() {
        f.state.setModels(Map.of("Editor", "big-model"));
        f.state.setWindows(book("big-model = 50000"));
        f.context(1, "Editor", 44_000, null, null); // 88 %
        assertThat(f.incidents("CONTEXT"))
                .singleElement()
                .satisfies(i -> assertThat(i).containsEntry("severity", "warn"));
        f.context(2, "Editor", 48_000, null, null); // 96 %
        assertThat(f.incidents("CONTEXT").get(0)).containsEntry("severity", "high");
    }

    @Test
    void aCatalogEntryAddedLaterReachesAnOpenRunAtOnce() {
        f.state.setModels(Map.of("Editor", "big-model"));
        f.context(1, "Editor", 44_000, null, null);
        assertThat(f.context("Editor")).doesNotContainKey("usable");
        assertThat(f.incidents("CONTEXT")).isEmpty();
        f.state.setWindows(book("big-model = 50000"));
        assertThat(f.context("Editor"))
                .containsEntry("usable", 50_000L)
                .containsEntry("windowSource", "catalog");
        assertThat(f.incidents("CONTEXT")).hasSize(1);
    }

    @Test
    void aCatalogEntryRemovedTakesTheFilledWindowAway() {
        f.state.setModels(Map.of("Editor", "big-model"));
        f.state.setWindows(book("big-model = 50000"));
        f.context(1, "Editor", 40_000, null, null);
        f.state.setWindows(WindowBook.EMPTY);
        assertThat(f.context("Editor")).doesNotContainKeys("usable", "windowSource");
    }

    @Test
    void theRegistryGivesTheCatalogToRunsItAlreadyHasAndToNewOnes() {
        RunRegistry registry = new RunRegistry();
        registry.setModels("p", ContextFixtures.TRACE, Map.of("Editor", "big-model"));
        registry.setWindows(book("big-model = 50000"));
        assertThat(registry.find("p", ContextFixtures.TRACE).orElseThrow().windows())
                .isEqualTo(book("big-model = 50000"));
        registry.setModels("p", "f".repeat(32), Map.of());
        assertThat(registry.find("p", "f".repeat(32)).orElseThrow().windows())
                .isEqualTo(book("big-model = 50000"));
    }
}
