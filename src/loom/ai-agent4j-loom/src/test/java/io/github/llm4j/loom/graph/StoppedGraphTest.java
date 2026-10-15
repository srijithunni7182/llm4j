package io.github.llm4j.loom.graph;

import static io.github.llm4j.loom.graph.GraphTestSupport.edges;
import static io.github.llm4j.loom.graph.GraphTestSupport.graphOf;
import static io.github.llm4j.loom.graph.GraphTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** An {@code on_stopped} handler is drawn like the other handlers, and a rewind in it draws its way back. */
class StoppedGraphTest {

    private static final String SCRIPT = """
            agent A { model: "m" }
            workflow W() {
                checkpoint c
                delegate "go" to A -> out
                    on_stopped {
                        alt (_stop.reason == "user") { rewind to c at most 1 times side effects: keep }
                        alt (_stop.reason == "timeout") { skip }
                    }
                note "after"
            }
            """;

    @Test
    void theHandlerHangsOffTheStepOnAStoppedEdge() {
        WorkflowGraph graph = graphOf(SCRIPT, "W");
        assertThat(edges(graph)).contains("n2->n4[stopped]");
        assertThat(node(graph, "n4").branch()).isEqualTo("stopped");
        assertThat(node(graph, "n4").parent()).isEqualTo("n2");
    }

    @Test
    void theOutcomesAreNodesAndTheRewindDrawsItsWayBack() {
        WorkflowGraph graph = graphOf(SCRIPT, "W");
        assertThat(graph.nodes().stream().map(GraphNode::kind).collect(Collectors.toList())).contains(Kinds.OUTCOME, Kinds.REWIND);
        assertThat(graph.nodes().stream().filter(n -> n.kind().equals(Kinds.OUTCOME)).map(GraphNode::label).collect(Collectors.toList())).containsExactly("skip");
        assertThat(edges(graph)).anyMatch(e -> e.endsWith("->n1[rewind]"));
    }

    @Test
    void mermaidDrawsTheStoppedEdgeDotted() {
        String mermaid = MermaidRenderer.render(graphOf(SCRIPT, "W"));
        assertThat(mermaid).contains("n2 -.->|stopped| n4");
    }

    @Test
    void aStepWithoutAHandlerHasNoStoppedEdge() {
        WorkflowGraph graph = graphOf("agent A { model: \"m\" }\nworkflow W() { delegate \"go\" to A -> out }", "W");
        assertThat(edges(graph)).noneMatch(e -> e.contains("stopped"));
    }
}
