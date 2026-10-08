package io.github.llm4j.loom.graph;

import static io.github.llm4j.loom.graph.GraphTestSupport.edges;
import static io.github.llm4j.loom.graph.GraphTestSupport.graphOf;
import static io.github.llm4j.loom.graph.GraphTestSupport.graphOfFile;
import static io.github.llm4j.loom.graph.GraphTestSupport.ids;
import static io.github.llm4j.loom.graph.GraphTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.NoteStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** V1.1 to V1.5: the shape of a workflow's graph. */
class GraphBuilderTest {

    private static final String AGENTS = "agent A { model: \"m\" }\nagent B { model: \"m\" }\n";

    @Test
    void theContentFactoryWorkflowHasEightNodesAndEightEdges() {
        WorkflowGraph graph = graphOfFile("samples/content_factory/main.loom", "GenerateContent");

        assertThat(ids(graph)).containsExactly("start", "n1", "n2", "n3", "n4", "n5", "n6", "end");
        assertThat(edges(graph)).containsExactly(
                "start->n1", "n1->n2", "n2->n3", "n3->n4", "n4->n5[then]", "n4->n6[else]", "n5->end", "n6->end");
        assertThat(graph.nodes().stream().map(GraphNode::kind).collect(Collectors.toList()))
                .containsExactly("start", "delegate", "parallel", "human_prompt", "alt", "note", "note", "end");
    }

    @Test
    void anAltLabelsItsTwoBranchesAndBothJoinTheNextStep() {
        WorkflowGraph graph = graphOf(AGENTS + """
                workflow W() {
                    alt (x == "1") {
                        note "a"
                    } else {
                        note "b"
                    }
                    note "after"
                }
                """, "W");

        assertThat(edges(graph)).containsExactly(
                "start->n1", "n1->n2[then]", "n1->n3[else]", "n2->n4", "n3->n4", "n4->end");
        assertThat(node(graph, "n2").parent()).isEqualTo("n1");
        assertThat(node(graph, "n2").branch()).isEqualTo("then");
        assertThat(node(graph, "n3").branch()).isEqualTo("else");
        assertThat(node(graph, "n4").parent()).isNull();
    }

    @Test
    void anAltWithAnEmptyBranchLeavesFromTheAltItself() {
        WorkflowGraph graph = graphOf(AGENTS + """
                workflow W() {
                    alt (x == "1") {
                        note "a"
                    }
                    note "after"
                }
                """, "W");

        assertThat(edges(graph)).containsExactly("start->n1", "n1->n2[then]", "n2->n3", "n1->n3", "n3->end");
    }

    @Test
    void aLoopHasABodyAnAgainEdgeBackAndADoneEdgeOut() {
        WorkflowGraph graph = graphOf(AGENTS + """
                workflow W() {
                    loop until (x == "1") max 4 {
                        delegate "one" to A -> a
                        delegate "two" to B -> b
                    }
                    note "after"
                }
                """, "W");

        assertThat(edges(graph)).containsExactly(
                "start->n1", "n1->n2", "n2->n3", "n3->n1[again]", "n1->n4[done]", "n4->end");
        assertThat(node(graph, "n1").bound()).isEqualTo(4);
        assertThat(node(graph, "n2").parent()).isEqualTo("n1");
        assertThat(node(graph, "n2").branch()).isEqualTo("body");
    }

    @Test
    void aForEachHasTheShapeOfALoopAndOnlyAParallelOneIsFlagged() {
        WorkflowGraph graph = graphOf(AGENTS + """
                workflow W() {
                    for each x in items {
                        delegate "one" to A -> a
                    }
                    parallel for each y in items {
                        delegate "two" to B -> b
                    }
                }
                """, "W");

        assertThat(edges(graph)).containsExactly(
                "start->n1", "n1->n2", "n2->n1[again]", "n1->n3[done]", "n3->n4", "n4->n3[again]", "n3->end[done]");
        assertThat(node(graph, "n1").attrs()).doesNotContainKey("parallel");
        assertThat(node(graph, "n3").attrs()).containsEntry("parallel", true);
    }

    @Test
    void aParallelBlockIsOneNodeStandingForTheWholeRound() {
        WorkflowGraph graph = graphOf(AGENTS + """
                workflow W() {
                    parallel {
                        delegate "one" to A -> a
                        delegate "two" to B -> b
                        delegate "three" to A -> c
                    }
                }
                """, "W");

        assertThat(ids(graph)).containsExactly("start", "n1", "end");
        assertThat(node(graph, "n1").kind()).isEqualTo("parallel");
        assertThat(node(graph, "n1").label()).isEqualTo("in parallel: A, B");
        assertThat(node(graph, "n1").attrs()).containsEntry("agents", List.of("A", "B")).containsEntry("branches", 3L);
    }

    @Test
    void everyStatementKindGetsItsOwnKind() {
        WorkflowGraph graph = graphOfFile("src/test/resources/graph/all_statements.loom", "Everything");

        Set<String> kinds = graph.nodes().stream().map(GraphNode::kind).collect(Collectors.toSet());
        assertThat(kinds).containsExactlyInAnyOrder(
                "start", "end", "delegate", "task", "broadcast", "parallel", "human_prompt", "alt", "note", "observe",
                "loop", "foreach", "guardrail", "decide", "call", "rewind", "checkpoint", "handoff");
    }

    @Test
    void aStatementKindThisVersionDoesNotKnowIsDrawnAsUnknownNotDropped() {
        WorkflowDef workflow = new WorkflowDef("W");
        workflow.addStatement(new NoteStmt("first"));
        workflow.addStatement(new FutureStatement());

        WorkflowGraph graph = new GraphBuilder().build(workflow, "test.loom");

        assertThat(node(graph, "n2").kind()).isEqualTo("unknown");
        assertThat(node(graph, "n2").label()).isEqualTo("future");
        assertThat(edges(graph)).containsExactly("start->n1", "n1->n2", "n2->end");
    }

    @Test
    void handlersHangOffTheirOwnerOnLabelledEdgesAndRejoinTheMainPath() {
        WorkflowGraph graph = graphOfFile("src/test/resources/graph/handlers.loom", "Handlers");

        assertThat(edges(graph)).contains(
                "n2->n10[failure]", "n10->n11", "n3->n12[failure]", "n4->n13[exhausted]", "n6->n14[violation]",
                "n8->n15[still fails]", "n8->n1[rewind]");
        assertThat(node(graph, "n10").parent()).isEqualTo("n2");
        assertThat(node(graph, "n10").branch()).isEqualTo("failure");
        assertThat(node(graph, "n13").branch()).isEqualTo("exhausted");
        // the main path keeps its ids: handler nodes are numbered after it
        assertThat(node(graph, "n9").label()).isEqualTo("note");
        assertThat(node(graph, "n9").parent()).isNull();
        // a handler's exits join the next step of the main path, next to the owner's own exit
        assertThat(edges(graph)).contains("n2->n3", "n11->n3", "n3->n4", "n12->n4", "n4->n6[done]", "n13->n6", "n7->n8", "n14->n8");
    }

    @Test
    void aRewindToACheckpointThatIsNotThereIsMarkedUnresolvedAndHasNoWayBack() {
        WorkflowGraph graph = graphOf("workflow W() {\n  rewind to missing when (x == \"y\") at most 1 time\n}", "W");

        GraphNode rewind = node(graph, "n1");
        assertThat(rewind.unresolved()).isTrue();
        assertThat(edges(graph)).containsExactly("start->n1", "n1->end");
    }

    @Test
    void aRewindToAnEarlierCheckpointDrawsADashedWayBack() {
        WorkflowGraph graph = graphOfFile("src/test/resources/graph/handlers.loom", "Handlers");

        assertThat(node(graph, "n8").unresolved()).isFalse();
        assertThat(node(graph, "n8").label()).isEqualTo("rewind to first");
        assertThat(edges(graph)).contains("n8->n1[rewind]");
    }

    @Test
    void buildingTheSameScriptManyTimesInManyThreadsGivesTheSameGraph() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/graph/all_statements.loom"));
        WorkflowGraph expected = graphOf(source, "Everything");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<WorkflowGraph>> runs = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) {
                runs.add(pool.submit(() -> graphOf(source, "Everything")));
            }
            for (java.util.concurrent.Future<WorkflowGraph> run : runs) {
                assertThat(run.get()).isEqualTo(expected);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anEmptyWorkflowIsStartToEnd() {
        WorkflowGraph graph = graphOf("workflow W() { }", "W");

        assertThat(ids(graph)).containsExactly("start", "end");
        assertThat(edges(graph)).containsExactly("start->end");
    }

    @Test
    void nodesCarryTheLineOfTheirStatement() {
        WorkflowGraph graph = graphOfFile("samples/content_factory/main.loom", "GenerateContent");

        assertThat(node(graph, "n1").source()).isEqualTo(new SourceRef("test.loom", 5));
        assertThat(node(graph, "start").source()).isNull();
        assertThat(graph.line()).isEqualTo(4);
    }

    /** A statement from a future version of Loom. */
    private static final class FutureStatement implements Statement {
    }
}
