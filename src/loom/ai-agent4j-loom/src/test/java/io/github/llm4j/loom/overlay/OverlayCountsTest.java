package io.github.llm4j.loom.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.WorkflowGraph;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R34: what one run did on its graph. */
class OverlayCountsTest {

    @TempDir Path dir;

    private static String idOf(WorkflowGraph g, String labelStart) {
        return g.nodes().stream()
                .filter(n -> n.label().startsWith(labelStart))
                .map(GraphNode::id)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aStraightRunRanEachStatementOnceAndTookEveryEdge() throws Exception {
        var b = OverlayFixtures.build(dir, "straight");
        var o = RunOverlay.build(b.graph(), java.util.List.of(b.run()));
        var stats = o.workflows().get("Main");
        assertThat(stats.nodes().values()).allMatch(n -> n.ran() == 1 && n.skipped() == 0);
        assertThat(stats.edges().values()).containsOnly(1);
        assertThat(stats.edges()).hasSize(b.graph().workflows().get(0).edges().size());
        assertThat(o.neverReached()).isEmpty();
    }

    @Test
    void theBranchNotTakenIsSkippedAndItsEdgeIsNotTaken() throws Exception {
        var b = OverlayFixtures.build(dir, "triage order status");
        var o = RunOverlay.build(b.graph(), java.util.List.of(b.run()));
        WorkflowGraph g = b.graph().workflows().get(0);
        var stats = o.workflows().get("Main");
        String urgent = idOf(g, "note");
        String replier = idOf(g, "delegate");
        assertThat(stats.nodes().get(urgent).ran()).isZero();
        assertThat(stats.nodes().get(urgent).skipped()).isEqualTo(1);
        assertThat(stats.nodes().get(replier).ran()).isEqualTo(1);
        // the first alt went "else", the second went "then"
        var firstAlt =
                g.nodes().stream().filter(n -> n.kind().equals("alt")).findFirst().orElseThrow();
        assertThat(
                        g.edges().stream()
                                .filter(e -> e.from().equals(firstAlt.id()))
                                .filter(e -> stats.taken(e))
                                .map(e -> e.label()))
                .containsExactly("else");
        assertThat(
                        g.edges().stream()
                                .filter(e -> e.from().equals(firstAlt.id()))
                                .filter(e -> !stats.taken(e))
                                .map(e -> e.label()))
                .containsExactly("then");
        assertThat(o.neverReached())
                .extracting(RunOverlay.Unreached::label)
                .contains(
                        g.nodes().stream()
                                .filter(n -> n.id().equals(urgent))
                                .findFirst()
                                .orElseThrow()
                                .label());
    }

    @Test
    void theChoicesOfASingleRunAreListed() throws Exception {
        var b = OverlayFixtures.build(dir, "triage order status");
        var o = RunOverlay.build(b.graph(), java.util.List.of(b.run()));
        assertThat(o.decisions())
                .anyMatch(
                        d ->
                                d.kind().equals("alt")
                                        && d.condition().equals("intent.urgent == \"yes\"")
                                        && Boolean.FALSE.equals(d.result())
                                        && d.taken().equals("else"));
    }

    @Test
    void aLoopBodyRanOnceARound() throws Exception {
        var b = OverlayFixtures.build(dir, "loop until done");
        var o = RunOverlay.build(b.graph(), java.util.List.of(b.run()));
        WorkflowGraph g = b.graph().workflows().get(0);
        var stats = o.workflows().get("Main");
        assertThat(stats.nodes().get(idOf(g, "delegate")).ran()).isEqualTo(3);
        assertThat(stats.nodes().get(idOf(g, "loop")).ran()).isEqualTo(1);
        // every round ends by going back to the loop, which then asks again or lets go
        assertThat(
                        g.edges().stream()
                                .filter(e -> "again".equals(e.label()))
                                .map(e -> stats.edges().get(e.from() + "->" + e.to() + "|again")))
                .containsExactly(3);
        var loop =
                g.nodes().stream().filter(n -> n.kind().equals("loop")).findFirst().orElseThrow();
        assertThat(
                        g.edges().stream()
                                .filter(
                                        e ->
                                                e.from().equals(loop.id())
                                                        && !"body".equals(e.label()))
                                .map(
                                        e ->
                                                stats.edges()
                                                        .getOrDefault(
                                                                e.from() + "->" + e.to() + "|"
                                                                        + e.label(),
                                                                0)))
                .contains(1);
    }

    @Test
    void aForEachBodyRanOncePerItem() throws Exception {
        var b = OverlayFixtures.build(dir, "for each");
        var o = RunOverlay.build(b.graph(), java.util.List.of(b.run()));
        WorkflowGraph g = b.graph().workflows().get(0);
        var stats = o.workflows().get("Main");
        var worker =
                g.nodes().stream()
                        .filter(n -> n.label().contains("Worker"))
                        .findFirst()
                        .orElseThrow();
        assertThat(stats.nodes().get(worker.id()).ran()).isEqualTo(2);
        assertThat(stats.nodes().get(worker.id()).runs()).isEqualTo(1);
    }

    @Test
    void aFailedRunCountsItsFailure() throws Exception {
        var run = OverlayFixtures.build(dir, "straight").run();
        var failed =
                new RunOverlay.Run(
                        "f",
                        run.steps(),
                        run.choices(),
                        java.util.Set.of(run.steps().get(0)),
                        false);
        var b = OverlayFixtures.build(dir, "straight");
        var o = RunOverlay.build(b.graph(), java.util.List.of(failed));
        assertThat(o.workflows().get("Main").nodes().values()).anyMatch(n -> n.failures() == 1);
        // a run that did not finish takes no edge to the end
        assertThat(o.workflows().get("Main").edges().keySet()).noneMatch(k -> k.endsWith("->end"));
    }
}
