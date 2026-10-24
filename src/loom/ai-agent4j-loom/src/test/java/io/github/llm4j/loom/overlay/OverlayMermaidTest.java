package io.github.llm4j.loom.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R36: the path drawn in Mermaid. */
class OverlayMermaidTest {

    @TempDir Path dir;

    @Test
    void takenEdgesAreBoldAndTheRestAreDotted() throws Exception {
        var b = OverlayFixtures.build(dir, "triage order status");
        var o = RunOverlay.build(b.graph(), List.of(b.run()));
        String text =
                OverlayMermaid.render(b.graph().workflows().get(0), o.workflows().get("Main"));
        assertThat(text).startsWith("flowchart TD");
        assertThat(text).contains(" ==>|else| ").contains(" -.->|then| ");
        assertThat(text).contains("classDef ran").contains("classDef idle");
        long bold = text.lines().filter(l -> l.contains("==>")).count();
        long dotted = text.lines().filter(l -> l.contains("-.->")).count();
        assertThat(bold).isGreaterThan(0);
        assertThat(dotted).isGreaterThan(0);
        assertThat(bold + dotted).isEqualTo(b.graph().workflows().get(0).edges().size());
    }

    @Test
    void aStatementThatRanManyTimesShowsHowMany() throws Exception {
        var b = OverlayFixtures.build(dir, "loop until done");
        var o = RunOverlay.build(b.graph(), List.of(b.run()));
        String text =
                OverlayMermaid.render(b.graph().workflows().get(0), o.workflows().get("Main"));
        assertThat(text).contains(" ×3\"");
    }

    @Test
    void withoutRunsTheGraphIsDrawnAsBefore() throws Exception {
        var b = OverlayFixtures.build(dir, "straight");
        String plain =
                io.github.llm4j.loom.graph.MermaidRenderer.render(b.graph().workflows().get(0));
        assertThat(plain).doesNotContain("==>").doesNotContain("classDef");
    }
}
