package io.github.llm4j.evalreport.loom;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.eval.assertions.WorkflowAssertions;
import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.ExportConfig;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.eval.report.EvalRecorder;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A real Loom run with deterministic tasks, captured by the bridge: tasks are non-agent nodes in the graph, path and assertions. */
class TaskTraceExportTest {

    @TempDir Path root;

    private static final String SCRIPT =
            """
            workflow Main() {
                run RefundPolicy(amount = %d) -> verdict
                alt (verdict.outcome == "approved") {
                    run IssueRefund(amount = %d) -> receipt
                } else {
                    run Escalate(reason = verdict.reason) -> ticket
                }
            }
            """;

    @BeforeEach
    void setUp() {
        EvalRun.resetForTests();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-TASK-0002");
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

    private WorkflowTrace run(int amount) {
        LoomScript script = new LoomParser(new Lexer(SCRIPT.formatted(amount, amount)).tokenize()).parseScript();
        TaskRegistry tasks = new TaskRegistry()
                .register(Task.pure("RefundPolicy", c -> c.requireArg("amount", Integer.class) <= 50
                        ? TaskResult.outcome("approved")
                        : TaskResult.rejected("over the limit")))
                .register(Task.changes("IssueRefund", io.github.llm4j.agent.tool.EffectPolicy.DEFAULT, c -> TaskResult.value("rcpt-1")))
                .register(Task.pure("Escalate", c -> TaskResult.ok()));
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), model -> {
            throw new IllegalStateException("no model in this workflow");
        });
        executor.setTaskRegistry(tasks);
        LoomTrace trace = LoomTrace.attach(executor).workflow(script.getWorkflows().get(0));
        executor.initialize();
        executor.executeWorkflow("Main", new HashMap<>());
        return trace.finish(null);
    }

    @Test
    void tasksAreNonAgentNodesInTheGraph() {
        LoomScript script = new LoomParser(new Lexer(SCRIPT.formatted(1, 1)).tokenize()).parseScript();
        WorkflowGraph g = WorkflowGraph.of(script.getWorkflows().get(0));
        assertThat(g.nodes()).extracting(WorkflowTrace.Node::kind).containsExactly("start", "task", "alt", "task", "task", "end");
        assertThat(g.nodes()).extracting(WorkflowTrace.Node::label)
                .contains("run RefundPolicy", "run IssueRefund", "run Escalate");
        assertThat(g.nodes()).filteredOn(n -> n.kind().equals("task")).allSatisfy(n -> assertThat(n.agent()).isNull());
    }

    @Test
    void aRefusedRefundTakesTheElsePathAndNeverRunsThePayment() {
        WorkflowTrace wt = run(90);
        assertThat(wt.actualPath()).containsExactly("start", "n1", "n2", "n4", "end");
        assertThat(wt.events()).filteredOn(e -> e.type().equals("decision")).extracting(WorkflowTrace.Event::text).containsExactly("else");
        assertThat(wt.events()).filteredOn(e -> e.type().equals("task_start")).extracting(WorkflowTrace.Event::node).containsExactly("n1", "n4");
        WorkflowAssertions.assertThat(wt)
                .runsTasksInOrder("RefundPolicy", "Escalate")
                .runsTaskTimes("IssueRefund", 0)
                .taskEndedWith("RefundPolicy", "rejected")
                .takesBranch("n2", "else");
        assertThat(wt.agentsInOrder()).isEmpty();
    }

    @Test
    void anApprovedRefundTakesThePaymentPath() {
        WorkflowTrace wt = run(40);
        assertThat(wt.actualPath()).containsExactly("start", "n1", "n2", "n3", "end");
        WorkflowAssertions.assertThat(wt)
                .runsTasksInOrder("RefundPolicy", "IssueRefund")
                .runsTaskTimes("Escalate", 0)
                .taskEndedWith("IssueRefund", "ok")
                .takesBranch("n2", "then");
    }
}
