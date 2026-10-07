package io.github.llm4j.loom.task;

import static io.github.llm4j.loom.task.TaskHarness.map;
import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.HarnessExecutor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("fragile")
class TaskConcurrencyTest {

    private final TaskHarness h = new TaskHarness();

    @Test
    void parallelBlockOfTasks() {
        TaskHarness.Probe p = h.changes("Pay", c -> { Thread.sleep(5); return TaskResult.value(c.requireArg("n", Integer.class)); });
        StringBuilder body = new StringBuilder("workflow Main() { parallel {\n");
        for (int i = 0; i < 40; i++) body.append("run Pay(n = ").append(i).append(") -> r").append(i).append("\n");
        body.append("} }");
        HarnessExecutor e = h.executor(body.toString());
        e.initialize();
        e.executeWorkflow("Main", new HashMap<>());

        assertEquals(40, p.calls.get());
        for (int i = 0; i < 40; i++) assertEquals(i, map(e.getContext().getVariable("r" + i)).get("value"), "r" + i);
        Set<String> keys = new HashSet<>();
        synchronized (p.contexts) { p.contexts.forEach(c -> keys.add(c.idempotencyKey())); }
        assertEquals(40, keys.size(), "every call has its own idempotency key");
        Set<String> steps = new HashSet<>();
        synchronized (p.contexts) { p.contexts.forEach(c -> steps.add(c.stepId())); }
        assertEquals(40, steps.size(), "and its own step id");
        assertEquals(40, h.journal.all().entrySet().stream().filter(en -> en.getValue().kind().equals("effect_done")).count());
    }

    @Test
    void parallelForEach() {
        TaskHarness.Probe p = h.pure("Echo", c -> TaskResult.value(c.requireArg("id", String.class).toUpperCase()));
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) items.add(Map.of("id", "item" + i));
        HarnessExecutor e = h.executor("workflow Main() { parallel for each item in things { run Echo(id = item.id) -> {item.id} } }");
        e.initialize();
        e.getContext().setVariable("things", items);
        e.executeWorkflow("Main", new HashMap<>());
        assertEquals(50, p.calls.get());
        for (int i = 0; i < 50; i++) assertEquals("ITEM" + i, map(e.getContext().getVariable("item" + i)).get("value"));
    }

    @Test
    void aFailureHandlerDoesNotHoldUpOtherBranches() {
        java.util.concurrent.CountDownLatch handlerStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        h.register("Pay", TaskEffect.CHANGES, new EffectPolicy(EffectPolicy.OnUnknown.SKIP, false, 1), c -> TaskResult.ok());
        h.pure("WaitForRelease", c -> {
            handlerStarted.countDown();
            return TaskResult.value(released.await(5, java.util.concurrent.TimeUnit.SECONDS) ? "released" : "timeout");
        });
        h.changes("Other", c -> {
            if (!handlerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)) return TaskResult.value("handler never started");
            released.countDown();
            return TaskResult.value("done");
        });
        HarnessExecutor e = h.executor("""
                workflow Main() {
                    run Pay(id = "first") -> f
                    parallel {
                        run Pay(id = "second") -> s on_failure { run WaitForRelease() -> w }
                        run Other(id = "o") -> o
                    }
                }
                """);
        e.initialize();
        e.executeWorkflow("Main", new HashMap<>());
        assertEquals("released", map(e.getContext().getVariable("w")).get("value"),
                "the capped branch's on_failure block ran while another branch claimed its own call: the journal lock was not held across it");
        assertEquals("done", map(e.getContext().getVariable("o")).get("value"));
    }

    @Test
    void theCapHoldsAcrossParallelBranches() {
        TaskHarness.Probe p = h.register("Pay", TaskEffect.CHANGES, new EffectPolicy(EffectPolicy.OnUnknown.SKIP, false, 5), c -> TaskResult.ok());
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < 30; i++) items.add(Map.of("id", "i" + i));
        HarnessExecutor e = h.executor("""
                workflow Main() {
                    parallel for each item in things { run Pay(id = item.id) -> {item.id} on_failure { note "capped" } }
                }
                """);
        e.initialize();
        e.getContext().setVariable("things", items);
        e.executeWorkflow("Main", new HashMap<>());
        assertEquals(5, p.calls.get(), "claimed under one lock, so no branch slips past the limit");
    }
}
