package io.github.llm4j.eval.assertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.ExportConfig;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.eval.export.WorkflowTrace.Event;
import io.github.llm4j.eval.report.EvalRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Trajectory assertions for tasks: deterministic steps (plain code, no model) in a workflow. */
class WorkflowTaskAssertTest {

    @TempDir Path root;

    @BeforeEach
    void setUp() {
        EvalRun.resetForTests();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-TASK-0001");
        EvalRecorder.reset();
        EvalRecorder.activate();
        EvalRun.get().bindTest("com.acme.TaskTest", "refund");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ExportConfig.DIR);
        System.clearProperty(ExportConfig.RUN_ID);
        EvalRun.resetForTests();
        EvalRecorder.reset();
    }

    private static Event ev(double t, String type, String agent, Map<String, Object> data) {
        return new Event(t, type, agent, "Main/s0", null, "", data);
    }

    private static Event start(double t, String task) {
        return ev(t, "task_start", null, Map.of("task", task));
    }

    private static Event end(double t, String task, String outcome) {
        return ev(t, "task_end", null, Map.of("task", task, "outcome", outcome));
    }

    private static WorkflowTrace trace(Event... events) {
        return new WorkflowTrace(
                "wf", List.of(), List.of(), List.of(), List.of(), List.of(events), List.of(), null, 0, null);
    }

    private final WorkflowTrace refused =
            trace(
                    ev(0, "delegate_start", "Intake", Map.of()),
                    ev(1, "delegate_end", "Intake", Map.of()),
                    start(2, "RefundPolicy"),
                    end(3, "RefundPolicy", "rejected"),
                    start(4, "Escalate"),
                    end(5, "Escalate", "ok"));

    @Test
    void theTraceKnowsWhichTasksRanAndHowOften() {
        assertThat(refused.tasksInOrder()).containsExactly("RefundPolicy", "Escalate");
        assertThat(refused.taskSequence()).containsExactly("RefundPolicy", "Escalate");
        assertThat(refused.taskRuns("RefundPolicy")).isEqualTo(1);
        assertThat(refused.taskRuns("IssueRefund")).isZero();
        assertThat(refused.taskCounts()).containsExactly(Map.entry("RefundPolicy", 1L), Map.entry("Escalate", 1L));
        assertThat(refused.taskOutcomes("RefundPolicy")).containsExactly("rejected");
        assertThat(refused.agentsInOrder()).containsExactly("Intake");
    }

    @Test
    void aReplayedTaskDidNotRun() {
        WorkflowTrace replayed =
                trace(ev(0, "task_replayed", null, Map.of("task", "IssueRefund", "outcome", "ok")));
        assertThat(replayed.taskRuns("IssueRefund")).isZero();
        assertThat(replayed.tasksInOrder()).isEmpty();
    }

    @Test
    void repeatsAreCounted() {
        WorkflowTrace loop = trace(start(0, "Check"), start(1, "Check"), start(2, "Check"));
        assertThat(loop.taskRuns("Check")).isEqualTo(3);
        assertThat(loop.taskCounts()).containsExactly(Map.entry("Check", 3L));
        assertThat(loop.tasksInOrder()).containsExactly("Check");
    }

    @Test
    void passingAssertionsAreRecordedInTheWorkflowsFamily() throws Exception {
        WorkflowAssertions.assertThat(refused)
                .runsTasksInOrder("RefundPolicy", "Escalate")
                .runsTasksInOrder("Escalate")
                .runsTaskTimes("RefundPolicy", 1)
                .runsTaskTimes("IssueRefund", 0)
                .taskEndedWith("RefundPolicy", "rejected");
        EvalRun.get().finish();
        String evals = Files.readString(root.resolve("runs/RUN-TASK-0001/evaluations.jsonl"));
        assertThat(evals).contains("required-tasks-run");
    }

    @Test
    void failuresThrowWithAMessageThatNamesWhatHappened() {
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(refused).runsTasksInOrder("Escalate", "RefundPolicy"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("[Escalate, RefundPolicy]")
                .hasMessageContaining("[RefundPolicy, Escalate]");
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(refused).runsTaskTimes("IssueRefund", 1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("IssueRefund")
                .hasMessageContaining("0 time(s)");
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(refused).taskEndedWith("RefundPolicy", "approved"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("approved")
                .hasMessageContaining("[rejected]");
        assertThatThrownBy(() -> WorkflowAssertions.assertThat(refused).taskEndedWith("IssueRefund", "ok"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("[]");
    }
}
