package io.github.llm4j.loom.graph;

import static io.github.llm4j.loom.graph.GraphTestSupport.edges;
import static io.github.llm4j.loom.graph.GraphTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An {@code intentclassify} step in the graph: one node of kind {@code classify} showing its
 * backend, whether it is calibrated, and its labels (DESIGN 5.5, A14).
 */
class IntentClassifyGraphTest {

    private static final String JEV =
            """
            intentclassifier Intent {
                model: "Jev/jev-latest"
                choose kind: refund | order_status | complaint | other
                ask urgent: "Is the customer threatening to leave?"
                rate tone: calm | annoyed | angry
                confidence at least 0.8 else escalate
            }
            workflow Triage(message) {
                intentclassify Intent on message -> intent retry 2 timeout 30s on_failure { note "down" }
                alt (intent.kind == "refund") { note "refund" }
            }
            """;

    private static WorkflowGraph graph(String source) {
        LoomScript script = parse(source);
        WorkflowDef w = script.getWorkflows().get(0);
        return new GraphBuilder(Map.of(), GraphBuilder.classifiersOf(script)).build(w, "test.loom");
    }

    private static GraphNode classify(WorkflowGraph g) {
        return g.nodes().stream()
                .filter(n -> Kinds.CLASSIFY.equals(n.kind()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aClassifyStepIsOneNodeOfItsOwnKindWithItsBackendAndCalibration() {
        GraphNode n = classify(graph(JEV));

        assertThat(n.label()).isEqualTo("classify Intent");
        assertThat(n.attrs())
                .containsEntry("classifier", "Intent")
                .containsEntry("variable", "intent")
                .containsEntry("model", "Jev/jev-latest")
                .containsEntry("backend", "jev")
                .containsEntry("calibrated", true)
                .containsEntry("questions", 3L)
                .containsEntry("retry", 2L)
                .containsEntry("timeoutMs", 30_000L)
                .containsEntry("confidence", "at least 0.8 else escalate");
        assertThat(n.attrs()).doesNotContainKey("uncalibrated");
        assertThat((List<Object>) n.attrs().get("labels"))
                .containsExactly(
                        "kind: refund | order_status | complaint | other",
                        "tone: calm | annoyed | angry");
    }

    @Test
    void aChatBackendIsMarkedUncalibratedAndShowsItsSamples() {
        GraphNode n =
                classify(
                        graph(
                                JEV.replace("Jev/jev-latest", "gemini-2.5-flash")
                                        .replace(
                                                "model: \"gemini-2.5-flash\"",
                                                "model: \"gemini-2.5-flash\"\n samples: 3")));

        assertThat(n.attrs())
                .containsEntry("backend", "chat")
                .containsEntry("uncalibrated", true)
                .containsEntry("samples", 3L);
        assertThat(n.attrs()).doesNotContainKey("calibrated");
    }

    @Test
    void aDeclaredJevProviderIsStillJev() {
        GraphNode n =
                classify(
                        graph(
                                "provider Scorer { use: jev  api_key: env.K }\n"
                                        + JEV.replace("Jev/jev-latest", "Scorer/jev-latest")));

        assertThat(n.attrs()).containsEntry("backend", "jev").containsEntry("calibrated", true);
    }

    @Test
    void onFailureHangsOffTheNodeOnAFailureEdge() {
        WorkflowGraph g = graph(JEV);

        assertThat(edges(g)).anyMatch(e -> e.contains("[failure]"));
        assertThat(edges(g).get(0)).startsWith(Kinds.START + "->");
    }

    @Test
    void withoutTheClassifierDeclarationsTheNodeStillAppearsAsAClassifyStep() {
        LoomScript script = parse(JEV);
        WorkflowGraph g = new GraphBuilder().build(script.getWorkflows().get(0), "test.loom");

        GraphNode n = classify(g);
        assertThat(n.label()).isEqualTo("classify Intent");
        assertThat(n.attrs()).containsEntry("classifier", "Intent").doesNotContainKey("backend");
    }

    @Test
    void classifyIsAKnownKindSoNothingFallsBackToUnknown() {
        assertThat(Kinds.ALL).contains(Kinds.CLASSIFY);
        assertThat(graph(JEV).nodes()).noneMatch(n -> Kinds.UNKNOWN.equals(n.kind()));
    }

    @Test
    void theJsonAndTheMermaidCarryTheNode() {
        WorkflowGraph g = graph(JEV);

        String mermaid = MermaidRenderer.render(g);
        assertThat(mermaid).contains("classify Intent");
        GraphResult result =
                new GraphResult(
                        GraphResult.VERSION,
                        "test.loom",
                        List.of(),
                        List.of(g),
                        List.of(),
                        Map.of(),
                        List.of());
        String json = GraphJson.write(result);
        assertThat(json)
                .contains("\"kind\": \"classify\"")
                .contains("\"backend\": \"jev\"")
                .contains("\"calibrated\": true");
    }
}
