package io.github.llm4j.evalreport.loom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.eval.assertions.WorkflowAssertions;
import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.ExportConfig;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.eval.report.EvalRecorder;
import io.github.llm4j.evalreport.SchemaContractTest;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.TraceEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Loom bridge turns Loom trace events and the workflow AST into a neutral trace (LOOM-20..22).
 */
class LoomBridgeTest {

    @TempDir Path root;

    private static WorkflowDef workflow() {
        WorkflowDef w = new WorkflowDef("ResearchAndPublish");
        w.addStatement(new DelegateStmt("research", "Researcher", "findings"));
        AltStmt alt = new AltStmt("findings SUFFICIENT");
        LoopStmt loop =
                new LoopStmt("approved", List.of(new DelegateStmt("write", "Writer", "draft")));
        loop.setMaxIterations(3);
        alt.addIfStatement(loop);
        alt.addElseStatement(new DelegateStmt("research more", "Researcher", "more"));
        w.addStatement(alt);
        w.addStatement(new HandoffStmt("publish", "Publisher"));
        return w;
    }

    private static TraceEvent ev(
            long ms, String type, String agent, String text, Map<String, Object> data) {
        return new TraceEvent(
                type, agent, "main/s0", text, data, Instant.ofEpochMilli(1_000_000 + ms));
    }

    @BeforeEach
    void setUp() {
        EvalRun.resetForTests();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-LOOM-0001");
        EvalRecorder.reset();
        EvalRecorder.activate();
        EvalRun.get().bindTest("com.acme.WfTest", "flow");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ExportConfig.DIR);
        System.clearProperty(ExportConfig.RUN_ID);
        EvalRun.resetForTests();
        EvalRecorder.reset();
    }

    @Test
    void graphHasStableNodeIdsAndControlFlowEdges() {
        WorkflowGraph g = WorkflowGraph.of(workflow());
        assertThat(g.nodes())
                .extracting(WorkflowTrace.Node::id)
                .containsExactly("start", "n1", "n2", "n3", "n4", "n5", "n6", "end");
        assertThat(g.nodes())
                .extracting(WorkflowTrace.Node::kind)
                .containsExactly(
                        "start",
                        "delegate",
                        "alt",
                        "loop",
                        "delegate",
                        "delegate",
                        "handoff",
                        "end");
        assertThat(g.nodes().get(3).bound()).isEqualTo(3);
        assertThat(g.edges())
                .extracting(
                        e -> e.from() + "->" + e.to() + (e.label() == null ? "" : ":" + e.label()))
                .contains(
                        "start->n1",
                        "n1->n2",
                        "n2->n3:then",
                        "n2->n5:else",
                        "n4->n3:again",
                        "n3->n6:done",
                        "n5->n6",
                        "n6->end");
    }

    @Test
    void tracesBuildAPathInferBranchesAndLoopIterationsAndFeedAssertions() throws Exception {
        LoomTrace trace =
                LoomTrace.create()
                        .workflow(workflow())
                        .expectPath("start", "n1", "n2", "n3", "n4", "n3", "n4", "n6", "end")
                        .budgetUsd(1.0);
        var l = trace.listener();
        l.onEvent(
                ev(
                        0,
                        TraceEvent.DELEGATE_START,
                        "Researcher",
                        "research",
                        Map.of("variable", "findings")));
        l.onEvent(ev(100, TraceEvent.DELEGATE_END, "Researcher", "ok", Map.of()));
        l.onEvent(
                ev(
                        200,
                        TraceEvent.DELEGATE_START,
                        "Writer",
                        "draft 1",
                        Map.of("api_key", "sk-secretsecretsecret1234")));
        l.onEvent(ev(300, TraceEvent.DELEGATE_START, "Writer", "draft 2", Map.of()));
        l.onEvent(ev(400, TraceEvent.REWIND, null, "rewound", Map.of()));
        l.onEvent(ev(500, TraceEvent.DELEGATE_START, "Publisher", "publish", Map.of()));
        WorkflowTrace wt = trace.finish(null);

        assertThat(wt.actualPath())
                .containsExactly("start", "n1", "n2", "n3", "n4", "n3", "n4", "n6", "end");
        assertThat(wt.events())
                .anyMatch(
                        e ->
                                "decision".equals(e.type())
                                        && "n2".equals(e.node())
                                        && "then".equals(e.text())
                                        && Boolean.TRUE.equals(e.data().get("inferred")));
        assertThat(wt.rewinds()).isEqualTo(1);
        assertThat(wt.events().get(0).t()).isZero();
        assertThat(
                        wt.events().stream()
                                .map(WorkflowTrace.Event::data)
                                .filter(d -> d != null)
                                .anyMatch(d -> d.containsKey("api_key")))
                .as("credential-looking keys are dropped")
                .isFalse();
        assertThat(wt.agentsInOrder()).containsExactly("Researcher", "Writer", "Publisher");

        WorkflowAssertions.assertThat(wt)
                .followsExpectedPath()
                .visitsInOrder("Researcher", "Writer", "Publisher")
                .invokesAgents("Publisher")
                .takesBranch("n2", "then")
                .loopStopsWithin("n3", 3)
                .rewindsAtMost(2)
                .staysWithinSpend(1.0)
                .noSecretsInTrace();
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).loopStopsWithin("n3", 1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ran 2");
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).rewindsAtMost(0))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(
                        () ->
                                WorkflowAssertions.assertThat(wt)
                                        .visitsInOrder("Publisher", "Researcher"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).invokesAgents("Ghost"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).takesBranch("n2", "else"))
                .isInstanceOf(AssertionError.class);

        EvalRun.get().finish();
        Path dir = root.resolve("runs/RUN-LOOM-0001");
        assertThat(SchemaContractTest.violations(dir)).isEmpty();
        List<String> traces = Files.readAllLines(dir.resolve("traces.jsonl"));
        assertThat(traces).as("one trace for many assertions").hasSize(1);
        JsonNode t = SchemaContractTest.MAPPER.readTree(traces.get(0));
        assertThat(t.path("type").asText()).isEqualTo("WORKFLOW");
        assertThat(t.path("workflow").path("actualPath")).hasSize(9);
        assertThat(
                        Files.readAllLines(dir.resolve("evaluations.jsonl")).stream()
                                .filter(
                                        s ->
                                                s.contains("\"family\"")
                                                        || s.contains("loop-within-bound"))
                                .count())
                .isPositive();
        JsonNode run = SchemaContractTest.MAPPER.readTree(dir.resolve("run.json").toFile());
        assertThat(run.path("metrics").toString())
                .contains("\"family\":\"workflows\"")
                .contains("rewinds-within-cap");
    }

    @Test
    void noSecretsAssertionNamesWhereButNeverWhat() {
        LoomTrace trace = LoomTrace.create().named("w");
        trace.listener()
                .onEvent(
                        ev(
                                0,
                                TraceEvent.TOOL,
                                "A",
                                "calling with token=abcdef123456 now",
                                Map.of()));
        WorkflowTrace wt = trace.finish(null);
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).noSecretsInTrace())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("not shown")
                .hasMessageNotContaining("abcdef123456");
        assertThat(wt.nodes()).as("no AST: no graph (LOOM-03)").isEmpty();
    }

    @Test
    void parallelBranchesAndOverflowAreSafe() throws Exception {
        LoomTrace trace = LoomTrace.create().named("w");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            pool.submit(
                    () -> {
                        for (int j = 0; j < 3000; j++) {
                            trace.listener().onEvent(ev(j, TraceEvent.THOUGHT, "A", "x", null));
                        }
                    });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        WorkflowTrace wt = trace.finish(null);
        assertThat(wt.events().size()).isEqualTo(LoomTrace.MAX_EVENTS + 1);
        assertThat(wt.events().get(wt.events().size() - 1).type()).isEqualTo("truncated");
    }

    @Test
    void toolAssertionsReadToolEvents() {
        LoomTrace trace = LoomTrace.create().named("w");
        trace.listener().onEvent(ev(0, TraceEvent.TOOL, "A", "search", Map.of("tool", "search")));
        trace.listener().onEvent(ev(1, TraceEvent.TOOL, "A", "write", Map.of("tool", "write")));
        WorkflowTrace wt = trace.finish(null);
        WorkflowAssertions.assertThat(wt)
                .usesToolsInOrder("search", "write")
                .usesToolsExactly("search", "write")
                .callsOnlyAllowedTools(Set.of("search", "write"))
                .requestsApprovalBefore("n9")
                .outputMatchesSchema(Map.of("summary", "x"), "summary");
        assertThatThrownBy(
                        () ->
                                WorkflowAssertions.assertThat(wt)
                                        .callsOnlyAllowedTools(Set.of("search")))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(
                        () ->
                                WorkflowAssertions.assertThat(wt)
                                        .outputMatchesSchema(Map.of(), "summary"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).guardHeld("pii"))
                .isInstanceOf(AssertionError.class);
    }
}
