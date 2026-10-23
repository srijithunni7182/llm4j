package io.github.llm4j.loom.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R35: several runs on one graph, and the statements none of them reached. */
class OverlayAggregateTest {

    @TempDir Path dir;

    @Test
    void severalRunsAddUpAndNameTheStatementsNoneReached() throws Exception {
        var order = OverlayFixtures.build(dir, "triage order status");
        var other = OverlayFixtures.build(dir, "triage other");
        var o = RunOverlay.build(order.graph(), List.of(order.run(), other.run()));
        var g = order.graph().workflows().get(0);
        var stats = o.workflows().get("Main");
        var alts = g.nodes().stream().filter(n -> n.kind().equals("alt")).toList();
        // both runs went through the first alt; only the first reached the Replier
        assertThat(stats.nodes().get(alts.get(0).id()).ran()).isEqualTo(2);
        assertThat(stats.nodes().get(alts.get(0).id()).runs()).isEqualTo(2);
        var replier =
                g.nodes().stream()
                        .filter(n -> n.label().contains("Replier"))
                        .findFirst()
                        .orElseThrow();
        assertThat(stats.nodes().get(replier.id()).ran()).isEqualTo(1);
        assertThat(stats.nodes().get(replier.id()).runs()).isEqualTo(1);
        assertThat(stats.nodes().get(replier.id()).skipped()).isEqualTo(1);
        // nobody was urgent: the note and its return were never reached
        assertThat(o.neverReached())
                .extracting(RunOverlay.Unreached::label)
                .anyMatch(l -> l.startsWith("note"));
        assertThat(o.neverReached())
                .extracting(RunOverlay.Unreached::label)
                .noneMatch(l -> l.contains("Replier"));
        // with more than one run, no single run's choices are listed
        assertThat(o.decisions()).isEmpty();
        assertThat(o.runs()).containsExactly("triage order status", "triage other");
    }

    @Test
    void addingTheUrgentRunLeavesNothingUnreached() throws Exception {
        var order = OverlayFixtures.build(dir, "triage order status");
        var urgent = OverlayFixtures.build(dir, "triage urgent");
        var other = OverlayFixtures.build(dir, "triage other");
        var o = RunOverlay.build(order.graph(), List.of(order.run(), urgent.run(), other.run()));
        assertThat(o.neverReached()).isEmpty();
    }

    @Test
    void noRunsReachesNothing() throws Exception {
        var order = OverlayFixtures.build(dir, "triage order status");
        var o = RunOverlay.build(order.graph(), List.of());
        assertThat(o.neverReached()).isNotEmpty();
        assertThat(o.workflows().get("Main").nodes().values()).allMatch(n -> n.ran() == 0);
    }
}
