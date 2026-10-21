package io.github.llm4j.loom.graph;

import static io.github.llm4j.loom.graph.GraphTestSupport.graphOf;
import static io.github.llm4j.loom.graph.GraphTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.explain.ScriptExplainer;
import org.junit.jupiter.api.Test;

/** A {@code sleep} step in the graph and in the plain-words explanation. */
class SleepGraphTest {

    private static final String SOURCE =
            """
            agent A { model: "m" }
            workflow W() {
                delegate "x" to A -> x
                sleep 5m
                note "{x}"
            }
            """;

    @Test
    void aSleepIsOneNodeOfKindSleepCarryingItsDuration() {
        WorkflowGraph graph = graphOf(SOURCE, "W");
        GraphNode sleep =
                graph.nodes().stream()
                        .filter(n -> Kinds.SLEEP.equals(n.kind()))
                        .findFirst()
                        .orElseThrow();
        assertThat(sleep.label()).isEqualTo("sleep 5m");
        assertThat(sleep.attrs()).containsEntry("duration", "5m");
        assertThat(graph.nodes().stream().map(GraphNode::kind).toList())
                .containsExactly("start", "delegate", "sleep", "note", "end");
    }

    @Test
    void sleepIsAKnownKindSoNothingFallsBackToUnknown() {
        assertThat(Kinds.ALL).contains(Kinds.SLEEP);
        assertThat(graphOf(SOURCE, "W").nodes()).noneMatch(n -> Kinds.UNKNOWN.equals(n.kind()));
    }

    @Test
    void theExplanationSaysTheWorkflowSleeps() {
        String text = ScriptExplainer.explain(parse(SOURCE), "w.loom", "W");
        assertThat(text).contains("sleeps for 5m");
    }
}
