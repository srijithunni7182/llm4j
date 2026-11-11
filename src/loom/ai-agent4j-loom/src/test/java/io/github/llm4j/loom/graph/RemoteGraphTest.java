package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** docs/specs/32-a2a-integration R36: a remote agent and an ask are nodes of their own, with chips that say what matters. */
class RemoteGraphTest {

    private static final String HEAD = """
            agent Local { model: "gemini-2.0-flash" system: "x" }
            agent_channel Desk { audience: staff }
            remote Supplier {
                card: "https://supplier.example.com/.well-known/agent-card.json"
                agent_channel: Desk
                timeout: 2h
            }
            """;

    private static WorkflowGraph graph(String body) {
        var script = new LoomParser(new Lexer(HEAD + "workflow W() {\n" + body + "\n}\n").tokenize()).parseScript();
        return new GraphBuilder(Map.of(), Map.of(), GraphBuilder.a2aOf(List.of(script))).build(script.getWorkflows().get(0), "test.loom");
    }

    private static GraphNode node(WorkflowGraph g, String kind) {
        return g.nodes().stream().filter(n -> kind.equals(n.kind())).findFirst().orElseThrow();
    }

    @Test
    void aDelegateToARemoteIsARemoteNodeAndALocalDelegateStaysADelegate() {
        var g = graph("delegate \"q\" to Supplier -> quote\n delegate \"r\" to Local -> note2");
        assertThat(g.nodes().stream().map(GraphNode::kind)).contains(Kinds.REMOTE, Kinds.DELEGATE);
        GraphNode remote = node(g, Kinds.REMOTE);
        assertThat(remote.label()).isEqualTo("remote Supplier");
        assertThat(remote.attrs()).containsEntry("remote", "Supplier").containsEntry("channel", "Desk").containsEntry("audience", "staff");
        assertThat(remote.attrs()).containsEntry("host", "supplier.example.com").containsEntry("outsideBudgets", true);
    }

    @Test
    void aRemoteNodeShowsTheHostAndNeverTheWholeCardAddressOrAnyAuth() {
        var g = graph("delegate \"q\" to Supplier -> quote");
        assertThat(node(g, Kinds.REMOTE).attrs().toString()).doesNotContain(".well-known").doesNotContain("secret");
    }

    @Test
    void anAskIsANodeWithItsChannelAndAudienceAndTheQuestionCarriesNoSecretText() {
        var g = graph("ask Desk \"Which currency?\" -> currency expecting { currency: enum[\"USD\", \"INR\"] }");
        GraphNode ask = node(g, Kinds.ASK);
        assertThat(ask.label()).isEqualTo("ask Desk");
        assertThat(ask.attrs()).containsEntry("channel", "Desk").containsEntry("audience", "staff").containsEntry("variable", "currency");
        assertThat(ask.attrs().get("expecting")).isNotNull();
    }

    @Test
    void failureHandlersHangOffRemoteAndAskNodesLikeAnyStep() {
        var g = graph("delegate \"q\" to Supplier -> quote on_failure { halt \"no\" }\n ask Desk \"q\" -> r on_failure { halt \"no answer\" }");
        assertThat(g.edges().stream().map(GraphEdge::label)).contains("failure");
        assertThat(g.edges().stream().filter(e -> "failure".equals(e.label())).count()).isEqualTo(2);
    }

    @Test
    void theNewKindsAreInTheSchemaAndTheRenderer() throws Exception {
        String schema = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/io/github/llm4j/loom/graph/graph-result.schema.json"));
        assertThat(schema).contains("\"remote\"").contains("\"ask\"");
        String js = java.nio.file.Files.readString(java.nio.file.Path.of("../graph-render/graph-render.js"));
        assertThat(js).contains("remote:").contains("ask:");
    }
}
