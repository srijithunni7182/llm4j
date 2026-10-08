package io.github.llm4j.loom.task;

import static io.github.llm4j.loom.task.TaskHarness.map;
import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.execution.HarnessExecutor;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** What a {@code rewind} does to a task that changes things: the same rules as for a tool that changes things. */
class TaskRewindTest {

    private final TaskHarness h = new TaskHarness();

    private static final String SCRIPT = """
            workflow Main() {
                checkpoint a
                run Pay(x = 1) -> receipt
                run Check() -> verdict
                rewind to a when (verdict.outcome == "bad") at most 1 time side effects: %s
            }
            """;

    private TaskHarness.Probe check() {
        AtomicInteger n = new AtomicInteger();
        return h.pure("Check", c -> n.getAndIncrement() == 0 ? TaskResult.outcome("bad") : TaskResult.outcome("ok"));
    }

    @Test
    void keepDoesNotRepeatAnEffectAlreadyDone() {
        TaskHarness.Probe pay = h.changes("Pay", c -> TaskResult.value("paid"));
        TaskHarness.Probe check = check();
        HarnessExecutor e = h.executor(SCRIPT.formatted("keep"));
        e.initialize();
        e.executeWorkflow("Main", new HashMap<>());
        assertEquals(2, check.calls.get(), "the run went back and checked again");
        assertEquals(1, pay.calls.get(), "the payment from the first attempt was found, not repeated");
        assertEquals("paid", map(e.getContext().getVariable("receipt")).get("value"));
    }

    @Test
    void repeatRunsAnIdempotentTaskAgainWithAFreshKey() {
        TaskHarness.Probe pay = h.idempotent("Pay", c -> TaskResult.value("paid"));
        TaskHarness.Probe check = check();
        HarnessExecutor e = h.executor(SCRIPT.formatted("repeat"));
        e.initialize();
        e.executeWorkflow("Main", new HashMap<>());
        assertEquals(2, check.calls.get());
        assertEquals(2, pay.calls.get(), "side effects: repeat runs it again in the new attempt");
        assertNotEquals(pay.contexts.get(0).idempotencyKey(), pay.contexts.get(1).idempotencyKey(), "a deliberate repeat is a new payment");
    }

    @Test
    void repeatOverANonIdempotentTaskIsRefusedByTheCheck() {
        h.changes("Pay", c -> TaskResult.value("paid"));
        check();
        HarnessExecutor e = h.executor(SCRIPT.formatted("repeat"));
        RuntimeException ex = assertThrows(RuntimeException.class, e::initialize);
        assertTrue(ex.getMessage().contains("not idempotent"), ex.getMessage());
    }

    @Test
    void aRewindWithNoStatedPolicyOverAPaymentWarnsAndStillKeepsByDefaultInTheRuntime() {
        TaskHarness.Probe pay = h.changes("Pay", c -> TaskResult.value("paid"));
        check();
        String script = SCRIPT.replace(" side effects: %s", "");
        HarnessExecutor e = h.executor(script);
        // "ask first" is the default and needs a person; the scripted human says yes to keeping
        e.initialize();
        e.executeWorkflow("Main", new HashMap<>());
        assertEquals(1, pay.calls.get(), "never paid twice, whatever the answer to the question");
    }
}
