package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R1: the history of an agent's context, for the timeline: points per model call, events per
 * compaction, bounded.
 */
class ContextHistoryTest {

    private final ContextFixtures f = new ContextFixtures();

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> points(String agent) {
        return (List<Map<String, Object>>) f.context(agent).get("points");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(String agent) {
        return (List<Map<String, Object>>) f.context(agent).get("events");
    }

    @Test
    void eachModelCallIsAPointWithItsCallNumberUsedTokensAndFraction() {
        f.context(1, "Editor", 400, 1200L, 1000L);
        f.context(2, "Editor", 700, 1200L, 1000L);
        assertThat(points("Editor")).hasSize(2);
        assertThat(points("Editor").get(0))
                .containsEntry("n", 1L)
                .containsEntry("used", 400L)
                .containsEntry("fraction", 0.4);
        assertThat(points("Editor").get(1))
                .containsEntry("n", 2L)
                .containsEntry("used", 700L)
                .containsEntry("fraction", 0.7);
    }

    @Test
    void aCompactionIsAnEventAfterTheCallItFollowed() {
        f.context(1, "Editor", 900, 1200L, 1000L);
        f.compaction(1.5, "Editor", "COMPACTED", 900, 500);
        f.context(2, "Editor", 520, 1200L, 1000L);
        assertThat(events("Editor"))
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e)
                                    .containsEntry("n", 1L)
                                    .containsEntry("strategy", "drop_oldest")
                                    .containsEntry("outcome", "COMPACTED")
                                    .containsEntry("before", 900L)
                                    .containsEntry("after", 500L);
                        });
    }

    @Test
    void aCompactionThatFreedNothingIsStillAnEventWithItsOutcome() {
        f.context(1, "Editor", 900, 1200L, 1000L);
        f.compaction(2, "Editor", "NO_GAIN", 900, 900);
        assertThat(events("Editor"))
                .singleElement()
                .satisfies(e -> assertThat(e).containsEntry("outcome", "NO_GAIN"));
    }

    @Test
    void twoAgentsKeepTheirOwnHistory() {
        f.context(1, "A", 100, 1200L, 1000L);
        f.context(2, "B", 200, 1200L, 1000L);
        f.context(3, "A", 300, 1200L, 1000L);
        f.compaction(4, "B", "COMPACTED", 200, 50);
        assertThat(points("A")).extracting(p -> p.get("used")).containsExactly(100L, 300L);
        assertThat(points("B")).extracting(p -> p.get("used")).containsExactly(200L);
        assertThat(events("A")).isEmpty();
        assertThat(events("B")).hasSize(1);
    }

    @Test
    void anAgentWithNoWindowStillHasPointsOfTokensWithoutAFraction() {
        f.context(1, "Writer", 400, null, null);
        assertThat(points("Writer"))
                .singleElement()
                .satisfies(
                        p ->
                                assertThat(p)
                                        .containsEntry("used", 400L)
                                        .doesNotContainKey("fraction"));
    }

    @Test
    void theOldSeriesIsUnchanged() {
        f.context(1, "Editor", 500, 1200L, 1000L);
        f.context(2, "Editor", 800, 1200L, 1000L);
        assertThat(f.context("Editor").get("series")).isEqualTo(List.of(0.5, 0.8));
    }

    @Test
    void pointsAndEventsAreBoundedAndKeepTheNewest() {
        for (int i = 1; i <= 300; i++) {
            f.context(i, "Editor", i, 1200L, 1000L);
            if (i % 2 == 0) f.compaction(i + 0.5, "Editor", "COMPACTED", i, i / 2);
        }
        assertThat(points("Editor")).hasSize(120);
        assertThat(points("Editor").get(119)).containsEntry("n", 300L);
        assertThat(points("Editor").get(0)).containsEntry("n", 181L);
        assertThat(events("Editor")).hasSize(60);
        assertThat(events("Editor").get(59)).containsEntry("n", 300L);
    }

    @Test
    void aSoakOfTwentyThousandCallsStaysBounded() {
        long start = System.nanoTime();
        for (int i = 1; i <= 20_000; i++) {
            f.context(i * 0.01, "A" + (i % 3), i % 1000, 1200L, 1000L);
            if (i % 7 == 0) f.compaction(i * 0.01 + 0.001, "A" + (i % 3), "COMPACTED", 900, 400);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
        for (String a : List.of("A0", "A1", "A2")) {
            assertThat(points(a).size()).isLessThanOrEqualTo(120);
            assertThat(events(a).size()).isLessThanOrEqualTo(60);
        }
        assertThat(f.state.view().toString().length()).isLessThan(500_000);
    }
}
