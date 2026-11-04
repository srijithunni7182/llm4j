package io.github.llm4j.loom.graph;

import static io.github.llm4j.loom.graph.GraphTestSupport.edges;
import static io.github.llm4j.loom.graph.GraphTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 24, R5: an {@code extract} step is one node of kind {@code extract} in the graph. */
class ExtractGraphTest {

    private static final String SOURCE =
            """
            extractor Invoice { model: "m" fields { vendor: string } }
            workflow Main(text, other) {
                extract Invoice from (email = text, order = other) -> invoice retry 1 timeout 30s on_failure { note "down" }
                note "done"
            }
            """;

    private static GraphNode node() {
        var script = parse(SOURCE);
        WorkflowGraph g = new GraphBuilder().build(script.getWorkflows().get(0), "test.loom");
        return g.nodes().stream()
                .filter(n -> Kinds.EXTRACT.equals(n.kind()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void anExtractStepIsANodeOfItsOwnKindLabelledWithTheExtractorAndItsInputs() {
        GraphNode n = node();

        assertThat(n.label()).isEqualTo("extract Invoice");
        assertThat(n.attrs())
                .containsEntry("extractor", "Invoice")
                .containsEntry("variable", "invoice")
                .containsEntry("retry", 1L)
                .containsEntry("timeoutMs", 30_000L);
        assertThat(((List<?>) n.attrs().get("inputs")).toArray()).containsExactly("email", "order");
    }

    @Test
    void theKindIsPartOfTheSchemaAndTheKindList() {
        assertThat(Kinds.ALL).contains(Kinds.EXTRACT);
    }

    @Test
    void aFailureEdgeLeavesTheStep() {
        var script = parse(SOURCE);
        WorkflowGraph g = new GraphBuilder().build(script.getWorkflows().get(0), "test.loom");

        assertThat(edges(g)).anyMatch(e -> e.contains("failure"));
    }
}
