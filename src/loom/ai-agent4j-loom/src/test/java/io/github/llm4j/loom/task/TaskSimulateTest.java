package io.github.llm4j.loom.task;

import static io.github.llm4j.loom.task.TaskHarness.map;
import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.TraceEvent;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskSimulateTest {

    private final TaskHarness h = new TaskHarness();

    @Test
    void aSimulatedRunOnlyRunsTasksThatChangeNothing() {
        TaskHarness.Probe pure = h.pure("Pure", c -> TaskResult.value("p"));
        TaskHarness.Probe reads = h.register("Reads", TaskEffect.READS, EffectPolicy.DEFAULT, c -> TaskResult.value("r"));
        TaskHarness.Probe pay = h.changes("Pay", c -> TaskResult.value("paid"));
        TaskHarness.Probe idem = h.idempotent("PayIdem", c -> TaskResult.value("paid"));
        HarnessExecutor e = h.executor("""
                workflow Main() {
                    run Pure() -> a
                    run Reads() -> b
                    run Pay(amount = 1) -> c
                    run PayIdem(amount = 1) -> d
                }
                """, x -> x.setSimulate(true));
        e.initialize();
        e.executeWorkflow("Main", new HashMap<>());

        assertEquals(1, pure.calls.get());
        assertEquals(1, reads.calls.get());
        assertEquals(0, pay.calls.get(), "described, not performed");
        assertEquals(0, idem.calls.get(), "idempotent or not, a simulation changes nothing");
        assertEquals("p", map(e.getContext().getVariable("a")).get("value"));
        assertEquals(Map.of("outcome", "simulated"), e.getContext().getVariable("c"));
        assertEquals(Map.of("outcome", "simulated"), e.getContext().getVariable("d"));
        assertTrue(h.journal.all().keySet().stream().noneMatch(k -> k.contains("#effect:")), "nothing recorded as pending or done");
        assertTrue(h.journal.all().entrySet().stream().noneMatch(en -> en.getKey().equals("Main/s2")), "a simulated step is not journaled as done");
        TraceEvent end = h.traceOf(TraceEvent.TASK_END).stream().filter(t -> "Pay".equals(t.data().get("task"))).findFirst().orElseThrow();
        assertEquals(true, end.data().get("simulated"));
    }

    @Test
    void aSimulationNeverAsksAPersonAboutATaskItWillNotRun() {
        java.util.List<String> questions = new java.util.ArrayList<>();
        h.human = message -> { questions.add(message); return "yes"; };
        TaskHarness.Probe[] probe = new TaskHarness.Probe[1];
        probe[0] = new TaskHarness.Probe();
        h.tasks.register(new io.github.llm4j.agent.task.Task() {
            @Override public String getName() { return "Pay"; }
            @Override public boolean requiresApproval(Map<String, Object> args) { return true; }
            @Override public TaskResult run(io.github.llm4j.agent.task.TaskContext c) { probe[0].calls.incrementAndGet(); return TaskResult.ok(); }
        });
        HarnessExecutor e = h.executor("workflow Main() { run Pay(amount = 90) -> r }", x -> x.setSimulate(true));
        e.initialize();
        e.executeWorkflow("Main", new HashMap<>());
        assertEquals(0, probe[0].calls.get());
        assertTrue(questions.isEmpty(), "nobody is asked to approve something that is only being described: " + questions);
    }

    @Test
    void aSimulationDoesNotPoisonTheRealRun() {
        TaskHarness.Probe pay = h.changes("Pay", c -> TaskResult.value("paid"));
        String script = "workflow Main() { run Pay(amount = 1) -> c }";
        HarnessExecutor sim = h.executor(script, x -> x.setSimulate(true));
        sim.initialize();
        sim.executeWorkflow("Main", new HashMap<>());
        HarnessExecutor real = h.executor(script);
        real.initialize();
        real.executeWorkflow("Main", new HashMap<>());
        assertEquals(1, pay.calls.get(), "the real run on the same journal still pays");
        assertEquals("paid", map(real.getContext().getVariable("c")).get("value"));
    }

    @Test
    void maxPerRun() {
        TaskHarness.Probe pay = h.register("Pay", TaskEffect.CHANGES, new EffectPolicy(EffectPolicy.OnUnknown.SKIP, false, 2), c -> TaskResult.ok());
        HarnessExecutor e = h.executor("workflow Main() { run Pay(a = 1) -> x  run Pay(a = 2) -> y  run Pay(a = 3) -> z on_failure { note \"capped: {_error}\" } }");
        e.initialize();
        e.executeWorkflow("Main", new HashMap<>());
        assertEquals(2, pay.calls.get());
        assertEquals("", e.getContext().getVariable("z"), "never set (an unset variable reads as empty)");

        // without on_failure the run fails with the reason
        TaskHarness other = new TaskHarness();
        other.register("Pay", TaskEffect.CHANGES, new EffectPolicy(EffectPolicy.OnUnknown.SKIP, false, 1), c -> TaskResult.ok());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> other.run("workflow Main() { run Pay(a = 1) -> x  run Pay(a = 2) -> y }"));
        assertTrue(ex.getMessage().contains("limited to 1"), ex.getMessage());
    }

    @Test
    void maxPerRunDoesNotCountPureTasks() {
        TaskHarness.Probe p = h.register("Calc", TaskEffect.NONE, new EffectPolicy(EffectPolicy.OnUnknown.SKIP, false, 1), c -> TaskResult.ok());
        h.run("workflow Main() { run Calc(a = 1) -> x  run Calc(a = 2) -> y  run Calc(a = 3) -> z }");
        assertEquals(3, p.calls.get(), "the cap is for tasks that change things");
    }
}
