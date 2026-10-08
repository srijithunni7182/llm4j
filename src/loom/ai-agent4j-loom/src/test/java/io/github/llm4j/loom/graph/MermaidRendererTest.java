package io.github.llm4j.loom.graph;

import static io.github.llm4j.loom.graph.GraphTestSupport.graphOf;
import static io.github.llm4j.loom.graph.GraphTestSupport.graphOfFile;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** V3.2: the Mermaid text names every node and edge, with shapes per kind. */
class MermaidRendererTest {

    @Test
    void everyNodeAndEveryLabelledEdgeAppears() {
        WorkflowGraph graph = graphOfFile("samples/content_factory/main.loom", "GenerateContent");

        String text = MermaidRenderer.render(graph);

        assertThat(text).startsWith("flowchart TD\n");
        for (GraphNode node : graph.nodes()) {
            assertThat(text).contains(node.id());
        }
        assertThat(text).contains("n4 -->|then| n5").contains("n4 -->|else| n6");
        assertThat(text).contains("start([\"Start\"])").contains("end_([\"End\"])").contains("n4{{");
    }

    @Test
    void theEndNodeDoesNotUseMermaidsReservedWord() {
        String text = MermaidRenderer.render(graphOf("workflow W() { note \"x\" }", "W"));

        assertThat(text).contains("n1 --> end_").doesNotContain(" --> end\n");
    }

    @Test
    void quotesAndLineBreaksInLabelsCannotBreakTheDiagram() {
        GraphNode tricky = new GraphNode("n1", "note", "say \"hi\"\nthen \"bye\"", null, null, null, null, false, null, null, null);
        WorkflowGraph graph = new WorkflowGraph("W", "w.loom", 1, java.util.List.of(), java.util.List.of(tricky), java.util.List.of());

        String text = MermaidRenderer.render(graph);

        assertThat(text).contains("n1[\"say #quot;hi#quot; then #quot;bye#quot;\"]");
        assertThat(text.lines().count()).isEqualTo(2);
    }

    @Test
    void aRewindIsADashedEdgeAndEachKindHasItsOwnShape() {
        WorkflowGraph graph = graphOfFile("src/test/resources/graph/all_statements.loom", "Everything");

        String text = MermaidRenderer.render(graph);

        assertThat(text).contains("-.->|rewind|").contains("[[\"call Helper\"]]").contains(">\"checkpoint collected\"]")
                .contains("[/\"ask a person\"/]");
    }
}
