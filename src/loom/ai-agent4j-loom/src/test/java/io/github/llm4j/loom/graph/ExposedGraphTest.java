package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** docs/specs/32-a2a-integration R36: a workflow that outsiders may call is marked, with the names it is exposed as. */
class ExposedGraphTest {

    private static final String SOURCE = """
            agent_channel Desk { audience: caller }
            expose Totals { workflow: Total description: "Totals a receipt." agent_channel: Desk text_input: receipt }
            expose Sums { workflow: Total description: "Sums." agent_channel: Desk text_input: receipt }
            workflow Total(receipt) { return "ok" }
            workflow Private(receipt) { return "ok" }
            """;

    private static GraphResult result() {
        var script = new LoomParser(new Lexer(SOURCE).tokenize()).parseScript();
        var info = GraphBuilder.a2aOf(List.of(script));
        var builder = new GraphBuilder(Map.of(), Map.of(), info);
        return new GraphResult(1, "Total", List.of(), script.getWorkflows().stream().map(w -> builder.build(w, "t.loom")).toList(), List.of(), null, List.of());
    }

    @Test
    void aWorkflowThatIsExposedNamesEveryExposeBlockThatServesItAndOthersNameNone() {
        var graph = result();

        assertThat(graph.workflows().get(0).exposedAs()).containsExactly("Totals", "Sums");
        assertThat(graph.workflows().get(1).exposedAs()).isEmpty();
    }

    @Test
    void theJsonCarriesTheMarkerOnlyWhereThereIsOne() {
        String json = GraphJson.write(result());

        assertThat(json).contains("\"exposedAs\"").contains("Totals");
        assertThat(json.split("\"exposedAs\"", -1)).as("one marker, for one workflow").hasSize(2);
    }
}
