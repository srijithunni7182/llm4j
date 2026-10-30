package io.github.llm4j.loom.graph;

import static io.github.llm4j.loom.graph.GraphTestSupport.edges;
import static io.github.llm4j.loom.graph.GraphTestSupport.graphOf;
import static io.github.llm4j.loom.graph.GraphTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 21 R5: a {@code parallel} block is drawn as lanes. Its branches are nodes of their own
 * (with their lines and their {@code on_failure} handlers), grouped into lanes on the parallel
 * node, and nothing that existed before changes: the old attributes stay and no existing step is
 * renumbered.
 */
class ParallelLanesTest {

    private static final String SOURCE =
            """
            agent A { model: "m" }
            agent B { model: "m" }
            agent C { model: "m" }
            workflow W(t) {
                parallel {
                    delegate "x {t}" to A -> ra on_failure { note "a failed" }
                    delegate "y {t}" to B -> rb
                    delegate "z {t}" to C -> rc
                }
                note "after"
            }
            """;

    private static WorkflowGraph graph() {
        return graphOf(SOURCE, "W");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lanes(GraphNode parallel) {
        return (List<Map<String, Object>>) parallel.attrs().get("lanes");
    }

    @Test
    void theParallelNodeKeepsItsOldAttributesAndGainsLanes() {
        GraphNode parallel = node(graph(), "n1");

        assertThat(parallel.kind()).isEqualTo("parallel");
        assertThat(parallel.attrs()).containsEntry("agents", List.of("A", "B", "C")).containsEntry("branches", 3L);
        assertThat(lanes(parallel)).hasSize(3);
        assertThat(lanes(parallel)).extracting(l -> l.get("label")).containsExactly("A", "B", "C");
    }

    @Test
    void theStepAfterTheBlockKeepsTheIdItAlwaysHad() {
        assertThat(node(graph(), "n2").label()).isEqualTo("note");
    }

    @Test
    void everyBranchIsANodeInsideTheParallelNodeWithItsOwnLine() {
        WorkflowGraph graph = graph();
        List<GraphNode> branches = graph.nodes().stream().filter(n -> "n1".equals(n.parent()) && n.branch().startsWith("lane")).toList();

        assertThat(branches).extracting(GraphNode::kind).containsExactly("delegate", "delegate", "delegate");
        assertThat(branches).extracting(GraphNode::branch).containsExactly("lane 1", "lane 2", "lane 3");
        assertThat(branches).allSatisfy(n -> assertThat(n.source().line()).isPositive());
        assertThat(branches).extracting(GraphNode::agent).containsExactly("A", "B", "C");
    }

    @Test
    void aBranchsFailureHandlerIsInItsLaneAndHangsFromItsStep() {
        WorkflowGraph graph = graph();
        GraphNode first = graph.nodes().stream().filter(n -> "lane 1".equals(n.branch())).findFirst().orElseThrow();
        GraphNode handler = graph.nodes().stream().filter(n -> first.id().equals(n.parent()) && "failure".equals(n.branch())).findFirst().orElseThrow();

        assertThat(lanes(node(graph, "n1")).get(0).get("nodes")).isEqualTo(List.of(first.id(), handler.id()));
        assertThat(edges(graph)).contains(first.id() + "->" + handler.id() + "[failure]");
        assertThat(lanes(node(graph, "n1")).get(1).get("nodes")).isEqualTo(List.of(graph.nodes().stream().filter(n -> "lane 2".equals(n.branch())).findFirst().orElseThrow().id()));
    }

    @Test
    void theBlockForksIntoEveryBranchAndEveryBranchJoinsTheNextStep() {
        WorkflowGraph graph = graph();
        List<GraphNode> branches = graph.nodes().stream().filter(n -> n.branch() != null && n.branch().startsWith("lane")).toList();

        for (GraphNode branch : branches) {
            assertThat(edges(graph)).contains("n1->" + branch.id());
        }
        assertThat(edges(graph)).doesNotContain("n1->n2");
        for (GraphNode branch : branches) {
            assertThat(edges(graph).stream().anyMatch(e -> e.endsWith("->n2"))).isTrue();
        }
        assertThat(edges(graph).stream().filter(e -> e.endsWith("->n2")).count()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void anEmptyBlockStaysOneNodeJoinedStraightToTheNextStep() {
        WorkflowGraph graph = graphOf("workflow W() {\n parallel {\n }\n note \"x\"\n}\n", "W");

        assertThat(lanes(node(graph, "n1"))).isNull();
        assertThat(edges(graph)).contains("n1->n2");
    }

    @Test
    void aParallelBlockInsideALaneHasItsOwnLanes() {
        WorkflowGraph graph =
                graphOf(
                        "agent A { model: \"m\" }\nworkflow W() {\n parallel {\n  parallel {\n   delegate \"x\" to A -> a\n   delegate \"y\" to A -> b\n  }\n  delegate \"z\" to A -> c\n }\n}\n",
                        "W");

        assertThat(lanes(node(graph, "n1"))).hasSize(2);
        GraphNode inner = graph.nodes().stream().filter(n -> "parallel".equals(n.kind()) && "n1".equals(n.parent())).findFirst().orElseThrow();
        assertThat(lanes(inner)).hasSize(2);
    }

    @Test
    void theJsonCarriesTheLanesAndStillValidatesAgainstTheSchema(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("p.loom"), SOURCE);

        com.fasterxml.jackson.databind.JsonNode json =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(GraphJson.write(new GraphService().graph(script)));

        assertThat(GraphJsonTest.schema().validate(json)).isEmpty();
        assertThat(json.toString()).contains("\"lanes\"").contains("\"label\":\"A\"");
    }

    @Test
    void mermaidDrawsEachLaneAsASubgraphHoldingItsSteps() {
        String mermaid = MermaidRenderer.render(graph());

        assertThat(mermaid).contains("subgraph n1_lane1[\"A\"]").contains("subgraph n1_lane2[\"B\"]").contains("subgraph n1_lane3[\"C\"]");
        String firstLane = mermaid.substring(mermaid.indexOf("subgraph n1_lane1"), mermaid.indexOf("  end\n", mermaid.indexOf("subgraph n1_lane1")));
        assertThat(firstLane).contains("delegate A").contains("note");
        assertThat(mermaid.split("\n  end\n", -1).length - 1).as("one end per subgraph").isGreaterThanOrEqualTo(3);
    }
}
