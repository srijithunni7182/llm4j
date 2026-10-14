package io.github.llm4j.loom.task;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** A task or a delegate that fails is told in the trace: what failed, why, how often it was tried, and whether the workflow went on. */
class TaskFailureTraceTest {

    private final TaskHarness h = new TaskHarness();

    private static Map<String, Object> data(TraceEvent e) {
        return e.data();
    }

    @Test
    void aHandledTaskFailureSaysWhatFailedAndThatTheWorkflowWentOn() {
        h.pure("Boom", c -> { throw new IllegalStateException("kaput"); });
        h.run("workflow Main() { run Boom() -> a retry 2 on_failure { note \"handled\" } }");
        List<TraceEvent> failures = h.traceOf(TraceEvent.FAILURE);
        assertEquals(1, failures.size());
        Map<String, Object> d = data(failures.get(0));
        assertEquals("task", d.get("kind"));
        assertEquals("Boom", d.get("name"));
        assertEquals(true, d.get("handled"));
        assertEquals(3, ((Number) d.get("attempts")).intValue());
        assertEquals("IllegalStateException", d.get("error_type"));
        assertNotNull(d.get("category"));
        assertTrue(String.valueOf(d.get("error")).contains("kaput"));
        assertEquals("Main/s0", d.get("step"));
    }

    @Test
    void anUnhandledTaskFailureIsAFailureEventThenARunThatNamesTheTask() {
        h.pure("Boom", c -> { throw new IllegalStateException("kaput"); });
        assertThrows(RuntimeException.class, () -> h.run("workflow Main() { run Boom() -> a }"));
        Map<String, Object> d = data(h.traceOf(TraceEvent.FAILURE).get(0));
        assertEquals(false, d.get("handled"));
        Map<String, Object> end = data(h.traceOf(TraceEvent.RUN_END).get(0));
        assertEquals("failed", end.get("status"));
        assertEquals("task", end.get("failed_kind"));
        assertEquals("Boom", end.get("failed_name"));
        assertEquals("Main/s0", end.get("failed_step"));
        assertEquals(d.get("category"), end.get("failed_category"));
    }

    @Test
    void aFailedTaskEndCarriesTheSameVocabularyAsATool() {
        h.pure("Boom", c -> { throw new IllegalStateException("kaput"); });
        h.run("workflow Main() { run Boom() -> a retry 1 on_failure { note \"handled\" } }");
        Map<String, Object> d = data(h.traceOf(TraceEvent.TASK_END).get(0));
        assertEquals("failed", d.get("outcome"));
        assertEquals(2, ((Number) d.get("attempts")).intValue());
        assertEquals("IllegalStateException", d.get("error_type"));
        assertNotNull(d.get("category"));
        assertTrue(d.containsKey("millis") || d.containsKey("attempts"));
    }

    @Test
    void aFailureThatNeverStartedTheTaskIsStillAFailureEvent() {
        h.human = message -> "no";
        h.tasks.register(new Task() {
            @Override public String getName() { return "Pay"; }
            @Override public TaskEffect effect() { return TaskEffect.CHANGES; }
            @Override public boolean requiresApproval(Map<String, Object> args) { return true; }
            @Override public TaskResult run(TaskContext c) { return TaskResult.ok(); }
        });
        h.run("workflow Main() { run Pay(x = 1) -> a on_failure { note \"declined\" } }");
        Map<String, Object> d = data(h.traceOf(TraceEvent.FAILURE).get(0));
        assertEquals("Pay", d.get("name"));
        assertEquals(true, d.get("handled"));
        assertEquals(0, ((Number) d.get("attempts")).intValue());
    }

    @Test
    void aSuccessfulRunHasNeitherAFailureEventNorFailureFieldsOnRunEnd() {
        h.pure("Ok", c -> TaskResult.ok());
        h.run("workflow Main() { run Ok() -> a }");
        assertTrue(h.traceOf(TraceEvent.FAILURE).isEmpty());
        Map<String, Object> end = data(h.traceOf(TraceEvent.RUN_END).get(0));
        assertFalse(end.containsKey("failed_kind"));
    }

    @Test
    void aRunThatFailsForAnotherReasonHasNoFailedStepFields() {
        // the workflow does not exist: nothing about a task or a delegate
        HarnessExecutor e = h.executor("workflow Main() { note \"x\" }");
        e.initialize();
        assertThrows(RuntimeException.class, () -> e.executeWorkflow("Nope", new HashMap<>()));
        h.traceOf(TraceEvent.RUN_END).forEach(t -> assertFalse(t.data().containsKey("failed_kind")));
    }

    @Test
    void anExhaustedDelegateIsAFailureEventToo() {
        String src = """
                agent Writer { model: "m" system: "You are Writer." }
                workflow Main() { delegate "x" to Writer -> r on_failure { note "fallback" } }
                """;
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(src).tokenize()).parseScript(), h.tools, name -> new LLMClient() {
            @Override public LLMResponse chat(LLMRequest request) { throw new IllegalStateException("model down"); }
            @Override public Stream<LLMResponse> chatStream(LLMRequest request) { throw new IllegalStateException("model down"); }
        });
        e.addTraceListener(h.trace::add);
        e.initialize();
        e.executeWorkflow("Main", new HashMap<>());
        Map<String, Object> d = data(h.traceOf(TraceEvent.FAILURE).get(0));
        assertEquals("delegate", d.get("kind"));
        assertEquals("Writer", d.get("name"));
        assertEquals(true, d.get("handled"));
    }
}
