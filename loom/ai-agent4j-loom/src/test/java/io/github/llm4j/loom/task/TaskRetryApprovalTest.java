package io.github.llm4j.loom.task;

import static io.github.llm4j.loom.task.TaskHarness.map;
import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskNotPerformed;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskRetryApprovalTest {

    private final TaskHarness h = new TaskHarness();

    /** Runs without {@code initialize()}, so `weave check` rules (which refuse some of these scripts) are bypassed: this tests the runtime. */
    private HarnessExecutor bare(String script) {
        return h.executor(script);
    }

    private enum Kind {
        NONE(TaskEffect.NONE, EffectPolicy.DEFAULT),
        READS(TaskEffect.READS, EffectPolicy.DEFAULT),
        CHANGES(TaskEffect.CHANGES, EffectPolicy.DEFAULT),
        CHANGES_IDEMPOTENT(TaskEffect.CHANGES, new EffectPolicy(EffectPolicy.OnUnknown.SKIP, true, 0)),
        CHANGES_ON_UNKNOWN_RETRY(TaskEffect.CHANGES, new EffectPolicy(EffectPolicy.OnUnknown.RETRY, false, 0));

        final TaskEffect effect;
        final EffectPolicy policy;

        Kind(TaskEffect effect, EffectPolicy policy) {
            this.effect = effect;
            this.policy = policy;
        }
    }

    private int attempts(Kind kind, boolean notPerformed) {
        TaskHarness each = new TaskHarness();
        TaskHarness.Probe p = each.register("T", kind.effect, kind.policy, c -> {
            if (notPerformed) throw new TaskNotPerformed("nothing happened");
            throw new IllegalStateException("boom");
        });
        HarnessExecutor e = each.executor("workflow Main() { run T() -> r retry 2 }");
        assertThrows(RuntimeException.class, () -> e.executeWorkflow("Main", new HashMap<>()), kind + " " + notPerformed);
        return p.calls.get();
    }

    @Test
    void retryMatrix() {
        Map<String, Integer> expected = new java.util.LinkedHashMap<>();
        expected.put("NONE/not-performed", 3);
        expected.put("NONE/other", 3);
        expected.put("READS/not-performed", 3);
        expected.put("READS/other", 3);
        expected.put("CHANGES/not-performed", 3);
        expected.put("CHANGES/other", 1);                    // unknown outcome of a non-idempotent change: never repeated
        expected.put("CHANGES_IDEMPOTENT/not-performed", 3);
        expected.put("CHANGES_IDEMPOTENT/other", 3);         // the receiver deduplicates by key
        expected.put("CHANGES_ON_UNKNOWN_RETRY/not-performed", 3);
        expected.put("CHANGES_ON_UNKNOWN_RETRY/other", 3);
        Map<String, Integer> actual = new java.util.LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            actual.put(k + "/not-performed", attempts(k, true));
            actual.put(k + "/other", attempts(k, false));
        }
        assertEquals(expected, actual);
    }

    @Test
    void backoffDoublesAndIsSkippedWhenNoRetryHappens() {
        h.pure("T", c -> { throw new IllegalStateException("boom"); });
        assertThrows(RuntimeException.class, () -> bare("workflow Main() { run T() -> r retry 3 backoff 1s }").executeWorkflow("Main", new HashMap<>()));
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)), h.sleeps);

        h.sleeps.clear();
        TaskHarness other = new TaskHarness();
        other.changes("T", c -> { throw new IllegalStateException("boom"); });
        assertThrows(RuntimeException.class, () -> other.executor("workflow Main() { run T() -> r retry 3 backoff 1s }").executeWorkflow("Main", new HashMap<>()));
        assertTrue(other.sleeps.isEmpty(), "no retry, no waiting");
    }

    @Test
    void aRetrySucceedsAndBindsTheResult() {
        AtomicInteger n = new AtomicInteger();
        TaskHarness.Probe p = h.pure("T", c -> n.incrementAndGet() < 3 ? failWith("flaky") : TaskResult.value("ok after " + n.get()));
        HarnessExecutor e = bare("workflow Main() { run T() -> r retry 5 }");
        e.executeWorkflow("Main", new HashMap<>());
        assertEquals(3, p.calls.get());
        assertEquals("ok after 3", map(e.getContext().getVariable("r")).get("value"));
        assertEquals(1, h.traceOf("task_end").size());
    }

    private static TaskResult failWith(String message) {
        throw new IllegalStateException(message);
    }

    @Test
    void timeoutOfAPureTaskIsRetried() {
        TaskHarness.Probe p = h.pure("Slow", c -> { Thread.sleep(5_000); return TaskResult.ok(); });
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> bare("workflow Main() { run Slow() -> r timeout 50ms retry 1 }").executeWorkflow("Main", new HashMap<>()));
        assertEquals(2, p.calls.get());
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
    }

    @Test
    void timeoutOfANonIdempotentChangeIsAnUnknownOutcomeAndNotRetried() {
        TaskHarness.Probe p = h.changes("Slow", c -> { Thread.sleep(5_000); return TaskResult.ok(); });
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> bare("workflow Main() { run Slow() -> r timeout 50ms retry 3 }").executeWorkflow("Main", new HashMap<>()));
        assertEquals(1, p.calls.get());
        assertTrue(e.getMessage().contains("outcome is unknown"), e.getMessage());
    }

    @Test
    void aTimedOutTaskIsInterruptedNotLeftRunning() throws Exception {
        java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
        h.pure("Slow", c -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return TaskResult.ok();
        });
        assertThrows(RuntimeException.class, () -> bare("workflow Main() { run Slow() -> r timeout 50ms }").executeWorkflow("Main", new HashMap<>()));
        assertTrue(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "a task that outlives its timeout must be interrupted, or a payment could still complete after the step failed");
    }

    @Test
    void aFastTaskUnderATimeoutJustRuns() {
        h.pure("Quick", c -> TaskResult.value(1));
        HarnessExecutor e = bare("workflow Main() { run Quick() -> r timeout 5s }");
        e.executeWorkflow("Main", new HashMap<>());
        assertEquals(1, map(e.getContext().getVariable("r")).get("value"));
    }

    // ---- approval ------------------------------------------------------------------------------------------------

    private TaskHarness.Probe needsApproval(String name, TaskEffect effect) {
        TaskHarness.Probe probe = new TaskHarness.Probe();
        h.tasks.register(new Task() {
            @Override public String getName() { return name; }
            @Override public TaskEffect effect() { return effect; }
            @Override public boolean requiresApproval(Map<String, Object> args) { return ((Number) args.get("amount")).intValue() > 50; }
            @Override public TaskResult run(TaskContext c) { probe.calls.incrementAndGet(); return TaskResult.value("done"); }
        });
        return probe;
    }

    @Test
    void approvalYesRunsTheTask() {
        List<String> questions = new ArrayList<>();
        h.human = message -> { questions.add(message); return "yes"; };
        TaskHarness.Probe p = needsApproval("Pay", TaskEffect.CHANGES);
        h.run("workflow Main() { run Pay(amount = 90) -> r }");
        assertEquals(1, p.calls.get());
        assertEquals(1, questions.size());
        assertTrue(questions.get(0).contains("Pay") && questions.get(0).contains("90"), questions.get(0));
        assertTrue(h.audit.stream().anyMatch(a -> a.startsWith("approval_granted")), h.audit.toString());
    }

    @Test
    void noApprovalNeededBelowTheThreshold() {
        List<String> questions = new ArrayList<>();
        h.human = message -> { questions.add(message); return "yes"; };
        TaskHarness.Probe p = needsApproval("Pay", TaskEffect.CHANGES);
        h.run("workflow Main() { run Pay(amount = 10) -> r }");
        assertEquals(1, p.calls.get());
        assertTrue(questions.isEmpty());
    }

    @Test
    void approvalNoNeverRunsTheTaskAndIsNeverRetried() {
        List<String> questions = new ArrayList<>();
        h.human = message -> { questions.add(message); return "no"; };
        TaskHarness.Probe p = needsApproval("Pay", TaskEffect.NONE);
        RuntimeException e = assertThrows(RuntimeException.class, () -> h.run("workflow Main() { run Pay(amount = 90) -> r retry 3 }"));
        assertEquals(0, p.calls.get());
        assertEquals(1, questions.size(), "asked once, not once per retry");
        assertTrue(e.getMessage().contains("did not approve"), e.getMessage());
        assertTrue(h.audit.stream().anyMatch(a -> a.startsWith("approval_rejected")));

        // with on_failure the workflow goes on
        TaskHarness.Probe after = h.pure("After", c -> TaskResult.ok());
        h.journal = io.github.llm4j.loom.runtime.RunJournal.inMemory();
        h.run("workflow Main() { run Pay(amount = 90) -> r on_failure { run After() -> x } }");
        assertEquals(1, after.calls.get());
        assertEquals(0, p.calls.get());
    }

    @Test
    void approvalIsAskedOncePerDistinctArgumentsAndNotAgainOnResume() {
        List<String> questions = new ArrayList<>();
        h.human = message -> { questions.add(message); return "yes"; };
        TaskHarness.Probe p = needsApproval("Pay", TaskEffect.CHANGES);
        String script = "workflow Main() { run Pay(amount = 90) -> a  run Pay(amount = 95) -> b }";
        h.run(script);
        assertEquals(2, questions.size(), "two different calls, two questions");
        HarnessExecutor again = h.executor(script);
        again.initialize();
        again.executeWorkflow("Main", new HashMap<>());
        assertEquals(2, questions.size(), "a resumed run never asks twice");
        assertEquals(2, p.calls.get());
    }

    @Test
    void approvalCanSuspendTheRunAndAnAnswerResumesIt() {
        h.human = new io.github.llm4j.loom.runtime.HumanInterface() {
            @Override public String promptHuman(String message) { throw new IllegalStateException("unused"); }
            @Override public String promptHuman(String stepId, String message) { throw new RunSuspended(stepId, message); }
        };
        TaskHarness.Probe p = needsApproval("Pay", TaskEffect.CHANGES);
        String script = "workflow Main() { run Pay(amount = 90) -> r }";
        HarnessExecutor first = h.executor(script);
        first.initialize();
        RunSuspended waiting = assertThrows(RunSuspended.class, () -> first.executeWorkflow("Main", new HashMap<>()));
        assertEquals(0, p.calls.get(), "paused before the task");
        assertTrue(waiting.prompt().contains("Pay"));
        assertTrue(h.journal.all().keySet().stream().noneMatch(k -> k.contains("#effect:")), "nothing was claimed while waiting");

        h.journal.answer(waiting.stepId(), "yes");
        HarnessExecutor second = h.executor(script);
        second.initialize();
        second.executeWorkflow("Main", new HashMap<>());
        assertEquals(1, p.calls.get());
        assertEquals("done", map(second.getContext().getVariable("r")).get("value"));
    }
}
