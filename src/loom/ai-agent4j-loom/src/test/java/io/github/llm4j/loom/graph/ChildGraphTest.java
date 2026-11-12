package io.github.llm4j.loom.graph;

import static io.github.llm4j.loom.graph.GraphTestSupport.graphOf;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Phase 33, D16: a started workflow in the graph, apart from the entry node called start. */
class ChildGraphTest {

    private static final String SOURCE =
            """
            agent R { model: "m" system: "s" steerable: true streaming: true }
            workflow Research(t) { delegate "x" to R -> x  return x }
            workflow W(q) {
                start Research(t = q) budget { tokens: 10 } -> work
                supervise work { when tokens > 5 { cancel } }
                steer work R "Narrow."
                wait work timeout 1m -> r
                cancel work
            }
            """;

    @Test
    void theNewStatementsAreTheirOwnKindsAndTheEntryNodeKeepsItsName() {
        WorkflowGraph graph = graphOf(SOURCE, "W");

        assertThat(graph.nodes().stream().map(GraphNode::kind).toList())
                .containsExactly(
                        "start", "child_start", "child_watch", "child_watch", "child_wait", "child_cancel", "end");
        assertThat(graph.nodes().stream().noneMatch(n -> Kinds.UNKNOWN.equals(n.kind()))).isTrue();
        assertThat(Kinds.ALL).contains(Kinds.CHILD_START, Kinds.CHILD_WAIT, Kinds.CHILD_CANCEL, Kinds.CHILD_WATCH);
    }

    @Test
    void aDottedEdgeJoinsTheStartToEveryStatementThatUsesItsHandle() {
        WorkflowGraph graph = graphOf(SOURCE, "W");
        String start =
                graph.nodes().stream().filter(n -> Kinds.CHILD_START.equals(n.kind())).findFirst().orElseThrow().id();

        long handleEdges =
                graph.edges().stream().filter(e -> "handle".equals(e.label()) && e.from().equals(start)).count();

        assertThat(handleEdges).as("the supervise next to the start is joined by the plain edge already").isEqualTo(3);
    }

    @Test
    void aReportIsItsOwnKindWithTheFieldsItSets() {
        WorkflowGraph graph =
                graphOf(
                        """
                        workflow W(q) progress { stage: string, done: number } {
                            report { stage: "a", done: 1 }
                            set x = "1"
                        }
                        """,
                        "W");

        GraphNode report = graph.nodes().stream().filter(n -> Kinds.CHILD_REPORT.equals(n.kind())).findFirst().orElseThrow();
        assertThat(report.label()).isEqualTo("report stage, done");
        assertThat(Kinds.ALL).contains(Kinds.CHILD_REPORT);
        assertThat(graph.nodes().stream().noneMatch(n -> Kinds.UNKNOWN.equals(n.kind()))).isTrue();
    }

    @Test
    void notifyAndWaitSignalAreTheirOwnKindsWithTheSignalAndTheOtherSide() {
        WorkflowGraph graph =
                graphOf(
                        """
                        workflow Child(x) { wait signal "go" timeout 1m -> t  notify parent "done" "ok" }
                        workflow W(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            notify work "go" "now"
                            wait work signal "done" timeout 1m -> r
                        }
                        """,
                        "W");
        WorkflowGraph child = graphOf("workflow Child(x) { wait signal \"go\" timeout 1m -> t  notify parent \"done\" \"ok\" }", "Child");

        assertThat(graph.nodes().stream().map(GraphNode::kind).toList()).contains("child_notify", "child_signal_wait");
        assertThat(child.nodes().stream().map(GraphNode::kind).toList()).containsSubsequence("child_signal_wait", "child_notify");
        assertThat(Kinds.ALL).contains(Kinds.CHILD_NOTIFY, Kinds.CHILD_SIGNAL_WAIT);
        assertThat(child.nodes().stream().noneMatch(n -> Kinds.UNKNOWN.equals(n.kind()))).isTrue();
    }
}
