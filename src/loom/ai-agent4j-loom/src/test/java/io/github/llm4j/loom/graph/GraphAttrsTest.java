package io.github.llm4j.loom.graph;

import static io.github.llm4j.loom.graph.GraphTestSupport.graphOf;
import static io.github.llm4j.loom.graph.GraphTestSupport.graphOfFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** V1.7: a node carries the attributes written on its statement, and only those. */
class GraphAttrsTest {

    private static WorkflowGraph everything;

    @BeforeAll
    static void load() {
        everything = graphOfFile("src/test/resources/graph/all_statements.loom", "Everything");
    }

    private static GraphNode byLabel(String label) {
        return everything.nodes().stream().filter(n -> n.label().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void aDelegateCarriesRetryBackoffTimeoutAndBudget() {
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("tokens", 5000L);

        assertThat(byLabel("delegate Researcher").attrs()).containsExactly(
                entry("variable", "research"),
                entry("retry", 3L),
                entry("backoffMs", 2000L),
                entry("timeoutMs", 90000L),
                entry("budget", budget),
                entry("text", "Research {topic}"));
    }

    @Test
    void aDelegateCarriesTheOutlineOfTheSchemaItExpects() {
        GraphNode review = everything.nodes().stream()
                .filter(n -> "Review {draft}".equals(n.attrs().get("text")))
                .findFirst()
                .orElseThrow();

        assertThat(review.attrs()).containsEntry("expecting", "{score, notes}");
    }

    @Test
    void aRunCarriesItsTaskArgumentsAndRetry() {
        assertThat(byLabel("run RefundPolicy").attrs()).containsExactly(
                entry("task", "RefundPolicy"),
                entry("variable", "verdict"),
                entry("args", 1L),
                entry("retry", 2L),
                entry("timeoutMs", 30000L));
    }

    @Test
    void aBroadcastAndAParallelRoundListTheirAgents() {
        assertThat(byLabel("broadcast").attrs()).containsEntry("agents", List.of("Critic", "Copywriter"));
        assertThat(byLabel("in parallel: Copywriter").attrs())
                .containsExactly(entry("agents", List.of("Copywriter")), entry("branches", 2L));
    }

    @Test
    void aLoopCarriesItsBoundAndBudgetAndAForEachItsItemAndCollection() {
        GraphNode loop = everything.nodes().stream().filter(n -> n.kind().equals("loop")).findFirst().orElseThrow();
        assertThat(loop.bound()).isEqualTo(3);
        assertThat(loop.attrs()).containsKey("budget").doesNotContainKey("maxIterations");

        GraphNode each = byLabel("for each doc in docs");
        assertThat(each.attrs()).containsExactly(entry("item", "doc"), entry("collection", "docs"));
    }

    @Test
    void aCheckpointAndARewindCarryTheirSettings() {
        assertThat(byLabel("checkpoint collected").attrs().get("startingWith"))
                .isEqualTo(Map.of("feedback", "none", "round", "1"));
        Map<String, Object> rewind = byLabel("rewind to collected").attrs();
        assertThat(rewind).containsEntry("atMost", 2L).containsEntry("effects", "ask first");
        assertThat(rewind.get("carrying")).isEqualTo(Map.of("feedback", "{review.notes}"));
    }

    @Test
    void aCallAGuardrailAndADecisionCarryTheirNames() {
        assertThat(byLabel("call Helper").attrs()).containsEntry("variable", "helped").containsEntry("args", 1L);
        assertThat(byLabel("call Helper").call()).isEqualTo(new CallLink("Helper", null));
        assertThat(byLabel("guardrail PII").attrs()).containsExactly(entry("type", "PII"));
        assertThat(byLabel("decide Refund").attrs()).containsEntry("decision", "Refund").containsEntry("variable", "choice");
    }

    @Test
    void aDecisionShowsTheLevelItStartsAtWhenTheScriptDeclaresIt() {
        GraphBuilder builder = new GraphBuilder(Map.of("Refund", "suggest"));
        WorkflowGraph graph = builder.build(
                GraphTestSupport.parse("workflow W() { decide Refund -> choice }").getWorkflows().get(0), "t.loom");

        assertThat(graph.nodes().get(1).attrs()).containsEntry("level", "suggest");
    }

    @Test
    void aStatementWithOnlyItsRequiredPartsCarriesJustThoseAndNoValueIsNull() {
        WorkflowGraph graph = graphOf("workflow W() {\n  delegate \"x\" to A -> r\n}", "W");

        GraphNode delegate = graph.nodes().get(1);
        assertThat(delegate.attrs().keySet()).containsExactly("variable", "text");
        assertThat(everything.nodes()).allSatisfy(n -> assertThat(n.attrs().values()).doesNotContainNull());
    }

    @Test
    void longTextIsCutSoAHugePromptCannotBloatTheGraph() {
        String payload = "x".repeat(5000);
        WorkflowGraph graph = graphOf("workflow W() {\n  note \"" + payload + "\"\n}", "W");

        String text = (String) graph.nodes().get(1).attrs().get("text");
        assertThat(text).hasSize(200).endsWith("…");
    }
}
