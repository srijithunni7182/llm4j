package io.github.llm4j.loom.graph;

import static io.github.llm4j.loom.graph.GraphTestSupport.graphOf;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Phase 21: a parallel block inside a branch of another is drawn as a subgraph inside that lane,
 * and every step is declared once. Declared twice, Mermaid keeps one and draws the other lane
 * wrong.
 */
class NestedLanesMermaidTest {

    private static final String SCRIPT =
            """
            agent A { model: "m" system: "a" }
            agent B { model: "m" system: "b" }
            workflow W(t) {
                parallel {
                    delegate "x {t}" to A -> x
                    parallel {
                        delegate "y {t}" to B -> y
                        delegate "z {t}" to B -> z
                    }
                }
                return x
            }
            """;

    @Test
    void everyStepIsDeclaredExactlyOnce() {
        WorkflowGraph graph = graphOf(SCRIPT, "W");

        String text = MermaidRenderer.render(graph);

        for (GraphNode node : graph.nodes()) {
            if (node.id().equals("start") || node.id().equals("end")) continue;
            long declarations = text.lines().filter(l -> l.strip().startsWith(node.id() + "[") || l.strip().startsWith(node.id() + "(") || l.strip().startsWith(node.id() + "{")).count();
            assertThat(declarations).as("declarations of " + node.id()).isEqualTo(1);
        }
    }

    @Test
    void theNestedBlocksLanesAreSubgraphsInsideTheOuterLane() {
        String text = MermaidRenderer.render(graphOf(SCRIPT, "W"));

        java.util.List<String> lines = text.lines().toList();
        int outer = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).strip().matches("subgraph n\\d+_lane2\\[\"in parallel: B\"]")) outer = i;
        assertThat(outer).as("the outer lane holding the nested block").isNotNegative();
        int depth = lines.get(outer).indexOf('s');
        int end = outer + 1;
        while (!lines.get(end).equals(" ".repeat(depth) + "end")) end++;
        long nested = lines.subList(outer + 1, end).stream().filter(l -> l.strip().startsWith("subgraph ") && l.indexOf('s') > depth).count();
        assertThat(nested).as("the nested block's two lanes sit inside the outer lane").isEqualTo(2);
    }

    @Test
    void aBlockWithoutNestingIsDrawnAsBefore() {
        String text = MermaidRenderer.render(graphOf("agent A { model: \"m\" system: \"a\" }\nworkflow W(t) {\n parallel {\n delegate \"x\" to A -> x\n delegate \"y\" to A -> y\n }\n return x\n}\n", "W"));

        assertThat(text).contains("subgraph n1_lane1").contains("subgraph n1_lane2").doesNotContain("n5_lane");
    }
}
