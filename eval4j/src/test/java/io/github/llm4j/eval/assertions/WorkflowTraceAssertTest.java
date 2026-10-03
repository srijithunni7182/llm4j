package io.github.llm4j.eval.assertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.ExportConfig;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.eval.export.WorkflowTrace.Event;
import io.github.llm4j.eval.export.WorkflowTrace.SpendLine;
import io.github.llm4j.eval.report.EvalRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Trajectory assertions over the neutral workflow trace; no workflow engine involved. */
class WorkflowTraceAssertTest {

    @TempDir Path root;

    @BeforeEach
    void setUp() {
        EvalRun.resetForTests();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-WF-0001");
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

    private static Event ev(
            double t,
            String type,
            String agent,
            String node,
            String text,
            Map<String, Object> data) {
        return new Event(t, type, agent, "main/s0", node, text, data);
    }

    private static WorkflowTrace trace(List<Event> events) {
        return new WorkflowTrace(
                "wf",
                List.of(
                        new WorkflowTrace.Node("start", "start", "Start", null, null),
                        new WorkflowTrace.Node("n1", "delegate", "d", "A", null)),
                List.of(new WorkflowTrace.Edge("start", "n1", null)),
                List.of("start", "n1"),
                List.of("start", "n1"),
                events,
                List.of(
                        new SpendLine("main/s0", "A", "m", 10, 5, 1, 0.5, false),
                        new SpendLine(null, null, null, 0, 0, 0, null, true)),
                1.0,
                1,
                2);
    }

    @Test
    void passingChecksRecordWorkflowEvaluationsAndOneTrace() throws Exception {
        WorkflowTrace wt =
                trace(
                        List.of(
                                ev(0, "delegate_start", "A", "n1", "go", null),
                                ev(1, "tool", "A", "n1", "search", Map.of("tool", "search")),
                                ev(2, "approval", "A", "n2", "ask", null),
                                ev(3, "decision", "A", "n2", "then", null),
                                ev(
                                        4,
                                        "guard",
                                        "A",
                                        "n2",
                                        "pii guard applied",
                                        Map.of("blocked", "true")),
                                ev(5, "action", "A", "n3", "write", null)));
        WorkflowTraceAssert a = WorkflowAssertions.assertThat(wt);
        a.usesToolsInOrder("search", "write")
                .usesToolsExactly("search", "write")
                .callsOnlyAllowedTools(Set.of("search", "write"))
                .followsExpectedPath()
                .visitsInOrder("A")
                .invokesAgents("A")
                .takesBranch("n2", "then")
                .loopStopsWithin("n1", 1)
                .rewindsAtMost(1)
                .requestsApprovalBefore("n3")
                .staysWithinSpend(1.0)
                .guardHeld("pii")
                .outputMatchesSchema(Map.of("k", 1), "k")
                .noSecretsInTrace();
        assertThat(wt.totalCostUsd()).isEqualTo(0.5);
        WorkflowAssertions.assertThat(wt).rewindsAtMost(5);
        EvalRun.get().finish();
        Path dir = root.resolve("runs/RUN-WF-0001");
        assertThat(Files.readAllLines(dir.resolve("traces.jsonl"))).hasSize(1);
        String evals = Files.readString(dir.resolve("evaluations.jsonl"));
        assertThat(evals)
                .contains("follows-expected-path")
                .contains("spend-within-budget")
                .contains("typed-output-complete");
        assertThat(Files.readString(dir.resolve("run.json")))
                .contains("\"family\" : \"workflows\"");
    }

    @Test
    void everyFailureThrowsAnAssertionErrorAndIsRecordedAsFailed() throws Exception {
        WorkflowTrace wt =
                new WorkflowTrace(
                        "wf",
                        null,
                        null,
                        List.of("start", "x"),
                        List.of("start", "y"),
                        List.of(
                                ev(0, "tool", "A", "n1", "danger", null),
                                ev(1, "guard", "A", null, "pii guard", Map.of("note", "violation")),
                                ev(2, "tool", "B", null, "key = sk-abcdefghijklmnopqrstuv", null)),
                        List.of(new SpendLine("s", "A", "m", 1, 1, 1, 9.0, false)),
                        null,
                        5,
                        null);
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).usesToolsInOrder("zzz"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).usesToolsExactly("danger"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).callsOnlyAllowedTools(Set.of()))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).followsExpectedPath())
                .hasMessageContaining("took");
        assertThatThrownBy(
                        () ->
                                WorkflowAssertions.assertThat(wt.withExpectedPath(List.of()))
                                        .followsExpectedPath())
                .hasMessageContaining("No expected path");
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).staysWithinSpend(1.0))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).rewindsAtMost(1))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).guardHeld("pii"))
                .hasMessageContaining("breach");
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).guardHeld("other"))
                .hasMessageContaining("No event");
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).noSecretsInTrace())
                .hasMessageNotContaining("abcdefghijkl");
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(wt).requestsApprovalBefore("n1"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("none");
        EvalRun.get().finish();
        assertThat(Files.readString(root.resolve("runs/RUN-WF-0001/evaluations.jsonl")))
                .contains("\"passed\":false");
        assertThat(wt.agentsInOrder()).isEmpty();
    }
}
