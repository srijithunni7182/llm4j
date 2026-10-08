package io.github.llm4j.loom.task;

import static io.github.llm4j.loom.task.TaskHarness.map;
import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskJournalTest {

    private final TaskHarness h = new TaskHarness();

    private static final String PAY = """
            workflow Main() {
                run Pay(amount = 40) -> receipt
            }
            """;

    /** The journal key of the first step of {@code Main}. */
    private String stepKey() {
        return "Main/s0";
    }

    private void again(String script) {
        h.trace.clear();
        HarnessExecutor e = h.executor(script);
        e.initialize();
        e.executeWorkflow("Main", new HashMap<>());
    }

    @Test
    void replay() {
        TaskHarness.Probe a = h.pure("A", c -> TaskResult.value(1));
        TaskHarness.Probe b = h.changes("B", c -> TaskResult.value(2));
        TaskHarness.Probe c3 = h.idempotent("C", c -> TaskResult.value(3));
        String script = "workflow Main() { run A() -> a  run B() -> b  run C() -> c }";
        HarnessExecutor first = h.run(script);
        assertEquals(1, a.calls.get() + b.calls.get() + c3.calls.get() - 2);
        h.trace.clear();
        HarnessExecutor second = h.executor(script);
        second.initialize();
        second.executeWorkflow("Main", new HashMap<>());

        assertEquals(1, a.calls.get(), "nothing ran again");
        assertEquals(1, b.calls.get());
        assertEquals(1, c3.calls.get());
        assertEquals(first.getContext().getVariable("b"), second.getContext().getVariable("b"));
        assertEquals(3, map(second.getContext().getVariable("c")).get("value"));
        assertEquals(List.of(TraceEvent.TASK_REPLAYED, TraceEvent.TASK_REPLAYED, TraceEvent.TASK_REPLAYED),
                h.traceTypes().stream().filter(t -> t.startsWith("task")).toList());
        assertEquals("ok", h.traceOf(TraceEvent.TASK_REPLAYED).get(0).data().get("outcome"));
        assertEquals(0, h.modelCalls.get());
    }

    @Test
    void entriesHaveKindTask() {
        h.pure("A", c -> TaskResult.value(1));
        h.run("workflow Main() { run A() -> a }");
        RunJournal.Entry e = h.journal.get(stepKey()).orElseThrow();
        assertEquals("task", e.kind());
        assertEquals(java.util.Map.of("outcome", "ok", "value", 1), e.value());
    }

    @Test
    void replayAcrossAFileJournalGivesTheSameVariable(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        h.pure("A", c -> TaskResult.rejected("no").with("n", 2).with("list", List.of(1, "x")));
        h.journal = new io.github.llm4j.loom.runtime.FileRunJournal(dir.resolve("run.json"));
        HarnessExecutor first = h.run("workflow Main() { run A() -> a }");
        h.journal = new io.github.llm4j.loom.runtime.FileRunJournal(dir.resolve("run.json"));
        HarnessExecutor second = h.executor("workflow Main() { run A() -> a }");
        second.initialize();
        second.executeWorkflow("Main", new HashMap<>());
        assertEquals(first.getContext().getVariable("a"), second.getContext().getVariable("a"));
        assertEquals("no", map(second.getContext().getVariable("a")).get("reason"));
    }

    @Test
    void failedRecorded() {
        AtomicInteger handled = new AtomicInteger();
        TaskHarness.Probe flaky = h.pure("Flaky", c -> { throw new IllegalStateException("db down"); });
        h.pure("Handle", c -> { handled.incrementAndGet(); return TaskResult.value(c.requireArg("why", String.class)); });
        String script = """
                workflow Main() {
                    run Flaky() -> r on_failure { run Handle(why = "{_error}") -> h }
                }
                """;
        h.run(script);
        assertEquals(1, flaky.calls.get());
        assertEquals("failed", h.journal.get(stepKey()).orElseThrow().kind());
        again(script);
        assertEquals(1, flaky.calls.get(), "a recorded failure is not re-run on resume");
        assertEquals(1, handled.get(), "the handler's own step was journaled too, so it is replayed, not run twice");
        assertEquals(1, h.traceOf(TraceEvent.TASK_REPLAYED).size());
    }

    @Test
    void failureWithoutHandlerIsNotRecordedSoAResumeTriesAgain() {
        AtomicInteger n = new AtomicInteger();
        TaskHarness.Probe flaky = h.pure("Flaky", c -> {
            if (n.getAndIncrement() == 0) throw new IllegalStateException("db down");
            return TaskResult.ok();
        });
        String script = "workflow Main() { run Flaky() -> r }";
        assertThrows(RuntimeException.class, () -> h.run(script));
        assertTrue(h.journal.all().isEmpty() || h.journal.all().values().stream().noneMatch(e -> e.kind().equals("failed")));
        again(script);
        assertEquals(2, flaky.calls.get());
    }

    @Test
    void operatorRetry() {
        TaskHarness.Probe flaky = h.pure("Flaky", c -> { throw new IllegalStateException("db down"); });
        String script = "workflow Main() { run Flaky() -> r on_failure { note \"x\" } }";
        h.run(script);
        String key = stepKey();
        assertEquals("failed", h.journal.get(key).orElseThrow().kind());
        h.journal.put(key, new RunJournal.Entry("retry", "operator asked"));
        again(script);
        assertEquals(2, flaky.calls.get(), "an operator retry entry makes the step run again");
    }

    /** The process dies after the task acted but before the run wrote that down. */
    private CrashingJournal dieAfterTheEffect() {
        return new CrashingJournal(h.journal, (key, entry) -> entry.kind().equals("effect_done"));
    }

    @Test
    void crashWindowDefaultPolicyDoesNotRepeat() {
        TaskHarness.Probe pay = h.changes("Pay", c -> TaskResult.value("paid"));
        h.journal = dieAfterTheEffect();
        assertThrows(CrashingJournal.Crash.class, () -> h.run(PAY));
        assertEquals(1, pay.calls.get(), "the payment was made");
        h.journal = ((CrashingJournal) h.journal).delegateForTest();

        RuntimeException e = assertThrows(RuntimeException.class, () -> again(PAY));
        assertEquals(1, pay.calls.get(), "never repeated");
        assertTrue(e.getMessage().contains("outcome is unknown"), e.getMessage());
        assertTrue(e.getMessage().contains("retry"), "tells the operator what to do: " + e.getMessage());
        assertTrue(h.audit.stream().anyMatch(a -> a.startsWith("task_unknown")), h.audit.toString());
    }

    @Test
    void crashWindowIdempotentTaskRunsAgainWithTheSameKey() {
        TaskHarness.Probe pay = h.idempotent("Pay", c -> TaskResult.value("paid"));
        h.journal = dieAfterTheEffect();
        assertThrows(CrashingJournal.Crash.class, () -> h.run(PAY));
        h.journal = ((CrashingJournal) h.journal).delegateForTest();
        again(PAY);
        assertEquals(2, pay.calls.get());
        String k1 = pay.contexts.get(0).idempotencyKey();
        String k2 = pay.contexts.get(1).idempotencyKey();
        assertFalse(k1.isEmpty());
        assertEquals(k1, k2, "the receiver can recognise the repeat");
    }

    @Test
    void crashWindowOnUnknownRetryRunsAgain() {
        TaskHarness.Probe pay = h.register("Pay", TaskEffect.CHANGES, new EffectPolicy(EffectPolicy.OnUnknown.RETRY, false, 0),
                c -> TaskResult.value("paid"));
        h.journal = dieAfterTheEffect();
        assertThrows(CrashingJournal.Crash.class, () -> h.run(PAY));
        h.journal = ((CrashingJournal) h.journal).delegateForTest();
        again(PAY);
        assertEquals(2, pay.calls.get());
    }

    @Test
    void crashWindowOperatorRetryOverrides() {
        TaskHarness.Probe pay = h.changes("Pay", c -> TaskResult.value("paid"));
        h.journal = dieAfterTheEffect();
        assertThrows(CrashingJournal.Crash.class, () -> h.run(PAY));
        RunJournal underlying = ((CrashingJournal) h.journal).delegateForTest();
        h.journal = underlying;
        assertThrows(RuntimeException.class, () -> again(PAY));
        // an operator checked with the payment provider, found nothing, and asks for a retry
        underlying.put("Main/s0", new RunJournal.Entry("retry", "checked: not paid"));
        again(PAY);
        assertEquals(2, pay.calls.get());
        assertEquals("paid", map(underlying.get("Main/s0").orElseThrow().value()).get("value"));
    }

    @Test
    void crashBetweenEffectDoneAndStepRecordReusesTheResult() {
        TaskHarness.Probe pay = h.changes("Pay", c -> TaskResult.value("paid"));
        h.journal = new CrashingJournal(h.journal, (key, entry) -> entry.kind().equals("task"));
        assertThrows(CrashingJournal.Crash.class, () -> h.run(PAY));
        h.journal = ((CrashingJournal) h.journal).delegateForTest();
        again(PAY);
        assertEquals(1, pay.calls.get(), "effect_done was written, so the result is reused and the payment is not repeated");
        assertEquals(1, h.traceOf(TraceEvent.TASK_REPLAYED).size());
    }

    @Test
    void notPerformedIsSafeToRepeatEvenForANonIdempotentChange() {
        AtomicInteger n = new AtomicInteger();
        TaskHarness.Probe pay = h.changes("Pay", c -> {
            if (n.getAndIncrement() == 0) throw new io.github.llm4j.agent.task.TaskNotPerformed("card declined before charging");
            return TaskResult.value("paid");
        });
        // `weave check` refuses retry on such a task (see TaskValidationTest); the runtime is safe even when validation is bypassed
        HarnessExecutor e = h.executor("workflow Main() { run Pay(amount = 1) -> r retry 2 }");
        e.executeWorkflow("Main", new HashMap<>());
        assertEquals(2, pay.calls.get(), "retried after TaskNotPerformed: nothing had happened");
        assertEquals(0, h.sleeps.size());
        assertEquals("paid", map(e.getContext().getVariable("r")).get("value"));
        assertTrue(h.journal.all().values().stream().anyMatch(en -> en.kind().equals("effect_done")));
    }

    @Test
    void anUnknownFailureOfANonIdempotentChangeIsNeverRetriedEvenIfAskedTo() {
        TaskHarness.Probe pay = h.changes("Pay", c -> { throw new IllegalStateException("connection reset after sending"); });
        HarnessExecutor e = h.executor("workflow Main() { run Pay(amount = 1) -> r retry 3 }");
        RuntimeException failure = assertThrows(RuntimeException.class, () -> e.executeWorkflow("Main", new HashMap<>()));
        assertEquals(1, pay.calls.get());
        assertTrue(failure.getMessage().contains("outcome is unknown"), failure.getMessage());
        assertEquals("effect_pending", h.journal.all().entrySet().stream().filter(en -> en.getKey().contains("#effect:Pay")).findFirst().orElseThrow().getValue().kind(),
                "stays pending: the next run applies the policy");
    }

    @Test
    void idempotencyKeyRules() {
        TaskHarness.Probe nonEffect = h.pure("Pure", c -> TaskResult.ok());
        AtomicInteger n = new AtomicInteger();
        TaskHarness.Probe pay = h.register("Pay", TaskEffect.CHANGES, new EffectPolicy(EffectPolicy.OnUnknown.SKIP, true, 0), c -> {
            if (n.getAndIncrement() == 0) throw new IllegalStateException("timeout talking to the provider");
            return TaskResult.ok();
        });
        String script = "workflow Main() { run Pure() -> p  run Pay(a = 1) -> x retry 2  run Pay(a = 2) -> y }";
        h.run(script);
        assertEquals("", nonEffect.contexts.get(0).idempotencyKey(), "only a task that changes things gets a key");
        String attempt1 = pay.contexts.get(0).idempotencyKey();
        String attempt2 = pay.contexts.get(1).idempotencyKey();
        String otherStep = pay.contexts.get(2).idempotencyKey();
        assertEquals(attempt1, attempt2, "a retry of the same step keeps the key");
        assertNotEquals(attempt1, otherStep, "another step has another key");
        assertEquals(32, attempt1.length());

        // a different run (a fresh journal) never reuses yesterday's keys
        TaskHarness other = new TaskHarness();
        TaskHarness.Probe pay2 = other.idempotent("Pay", c -> TaskResult.ok());
        other.pure("Pure", c -> TaskResult.ok());
        other.run("workflow Main() { run Pure() -> p  run Pay(a = 1) -> x }");
        assertNotEquals(attempt1, pay2.contexts.get(0).idempotencyKey());
    }

    @Test
    void eachRoundOfALoopIsItsOwnStepWithItsOwnKey() {
        AtomicInteger round = new AtomicInteger();
        TaskHarness.Probe pay = h.idempotent("Pay", c -> TaskResult.value(round.incrementAndGet()));
        h.run("workflow Main() { loop until (r.value == \"3\") max 5 { run Pay(a = 1) -> r } }");
        assertEquals(3, pay.calls.get());
        assertEquals(3, pay.contexts.stream().map(c -> c.idempotencyKey()).distinct().count(), "same arguments, three rounds, three keys");
        assertEquals(3, pay.contexts.stream().map(c -> c.stepId()).distinct().count());
        assertEquals(3, h.journal.all().entrySet().stream().filter(en -> en.getValue().kind().equals("effect_done")).count());
        // and a resume replays all three rounds without a single new payment
        again("workflow Main() { loop until (r.value == \"3\") max 5 { run Pay(a = 1) -> r } }");
        assertEquals(3, pay.calls.get());
    }

    @Test
    void stepIdIsGiven() {
        TaskHarness.Probe p = h.pure("A", c -> TaskResult.ok());
        h.run("workflow Main() { note \"x\"  run A() -> a }");
        assertEquals("Main/s1", p.contexts.get(0).stepId());
    }
}
