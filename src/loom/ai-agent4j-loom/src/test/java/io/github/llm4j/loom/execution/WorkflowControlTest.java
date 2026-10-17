package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunHalted;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code halt}, {@code set}, {@code return} and {@code x is ok}: the language's own ways to end a run, hand back a value and ask "did it work?". */
class WorkflowControlTest {

    /** A task whose outcome is what its argument says, and which calls {@code passed} its only ok outcome. */
    private static final Task TEST_RUN = new Task() {
        @Override public String getName() { return "TestRun"; }
        @Override public TaskEffect effect() { return TaskEffect.NONE; }
        @Override public Set<String> okOutcomes() { return Set.of("passed"); }
        @Override public TaskResult run(TaskContext context) { return TaskResult.outcome(context.requireArg("outcome", String.class)).withValue("log"); }
    };

    private final AtomicInteger flakyCalls = new AtomicInteger();

    private TaskRegistry tasks() {
        return new TaskRegistry()
                .register(TEST_RUN)
                .register(Task.pure("Fine", c -> TaskResult.value("fine")))
                .register(Task.pure("Refuse", c -> TaskResult.rejected("no")))
                .register(Task.pure("Flaky", c -> flakyCalls.incrementAndGet() >= 3 ? TaskResult.ok() : TaskResult.outcome("not_yet")))
                .register(Task.changes("Writes", io.github.llm4j.agent.tool.EffectPolicy.DEFAULT, c -> TaskResult.value("written")))
                .register(Task.pure("Boom", c -> { throw new IllegalStateException("boom"); }));
    }

    private record Run(List<String> notes, List<TraceEvent> events, Throwable error, Map<String, Object> variables) {}

    private Run run(String script, String workflow) {
        return run(script, workflow, null, false);
    }

    private Run run(String script, String workflow, Path journal, boolean simulate) {
        LoomScript parsed = new LoomParser(new Lexer(script).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(parsed, new ToolRegistry(), model -> { throw new IllegalStateException("no model is used"); });
        executor.setTaskRegistry(tasks());
        executor.setSimulateTasks(simulate);
        if (journal != null) executor.setJournal(new FileRunJournal(journal));
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        executor.addTraceListener(events::add);
        executor.initialize();
        Throwable error = null;
        try {
            executor.executeWorkflow(workflow, Map.of("why", "the build is broken"));
        } catch (Throwable t) {
            error = t;
        }
        List<String> notes = events.stream().filter(e -> e.type().equals(TraceEvent.NOTE)).map(TraceEvent::text).toList();
        return new Run(notes, events, error, executor.getContext().getAll());
    }

    // ---- halt -----------------------------------------------------------------------------------------------------------------

    @Test
    void haltEndsTheRunWithItsReasonAndNothingAfterItRuns() {
        Run r = run("""
                workflow Main(why) {
                    note "before"
                    halt "stopped because {why}"
                    note "never"
                }
                """, "Main");

        assertInstanceOf(RunHalted.class, r.error());
        assertEquals("stopped because the build is broken", ((RunHalted) r.error()).reason());
        assertEquals(List.of("before"), r.notes());
        TraceEvent halt = r.events().stream().filter(e -> e.type().equals(TraceEvent.HALT)).findFirst().orElseThrow();
        assertEquals("stopped because the build is broken", halt.data().get("reason"));
        TraceEvent end = r.events().stream().filter(e -> e.type().equals(TraceEvent.RUN_END)).findFirst().orElseThrow();
        assertEquals("halted", end.data().get("status"), "its own status: not failed");
        assertEquals("stopped because the build is broken", end.data().get("reason"));
    }

    @Test
    void aHaltIsNotAStepFailureSoRetryOnFailureAndOnExhaustedNeverSeeIt() {
        Run inHandler = run("""
                workflow Main() {
                    run Boom() -> b retry 2 on_failure { halt "gave up" }
                    note "never"
                }
                """, "Main");
        assertEquals("gave up", ((RunHalted) inHandler.error()).reason());
        assertEquals(List.of(), inHandler.notes());

        Run inLoop = run("""
                workflow Main() {
                    loop until (done == "yes") max 3 {
                        halt "stop in the loop"
                    } on_exhausted {
                        note "exhausted handler ran"
                    }
                }
                """, "Main");
        assertEquals("stop in the loop", ((RunHalted) inLoop.error()).reason());
        assertFalse(inLoop.notes().contains("exhausted handler ran"));

        Run underOnFailure = run("""
                workflow Main() {
                    run Boom() -> b on_failure { note "handler ran" }
                    halt "after the handler"
                }
                """, "Main");
        assertEquals(List.of("handler ran"), underOnFailure.notes(), "an ordinary failure is still handled");
        assertEquals("after the handler", ((RunHalted) underOnFailure.error()).reason());
    }

    @Test
    void aHaltInACalledWorkflowEndsTheCallerToo() {
        Run r = run("""
                workflow Inner() {
                    halt "inner says no"
                }
                workflow Main() {
                    note "calling"
                    call Inner() -> x
                    note "never"
                }
                """, "Main");

        assertEquals("inner says no", ((RunHalted) r.error()).reason());
        assertEquals(List.of("calling"), r.notes());
    }

    @Test
    void aHaltInOneParallelBranchIsTheReasonTheRunEnded() {
        Run r = run("""
                workflow Main() {
                    parallel {
                        note "a branch finished"
                        halt "one branch halted"
                    }
                    note "never"
                }
                """, "Main");

        assertEquals("one branch halted", ((RunHalted) r.error()).reason());
        assertFalse(r.notes().contains("never"));
    }

    @Test
    void aHaltedRunResumedFromItsJournalHaltsAgainWithoutRunningAnythingElse(@TempDir Path dir) {
        String script = """
                workflow Main() {
                    run Fine() -> a
                    halt "no go"
                }
                """;
        Run first = run(script, "Main", dir.resolve("journal.json"), false);
        Run second = run(script, "Main", dir.resolve("journal.json"), false);

        assertEquals("no go", ((RunHalted) first.error()).reason());
        assertEquals("no go", ((RunHalted) second.error()).reason());
        assertEquals(1, second.events().stream().filter(e -> e.type().equals(TraceEvent.TASK_REPLAYED)).count(), "the step before it was replayed, not run again");
    }

    // ---- set and return ------------------------------------------------------------------------------------------------------

    @Test
    void setGivesTypedValuesAndReturnHandsAStructuredResultToTheCaller() {
        Run r = run("""
                workflow Stage(why) {
                    set label = "failed: {why}"
                    set count = 3
                    set ratio = 0.5
                    set flag = true
                    set copy = label
                    return { verdict: "PASSED", count: count, nested: { ok: flag, text: label } }
                    note "never reached"
                }
                workflow Main(why) {
                    call Stage(why = "{why}") -> got
                    alt (got.verdict == "PASSED") { note "verdict {got.verdict}" }
                    alt (got.count > 2) { note "count is a number: {got.count}" }
                    note "nested {got.nested.text} / {got.nested.ok}"
                }
                """, "Main");

        assertNull(r.error());
        assertEquals(List.of("verdict PASSED", "count is a number: 3", "nested failed: the build is broken / true"), r.notes());
        assertEquals(3L, ((Map<?, ?>) r.variables().get("got")).get("count"), "a number stays a number");
    }

    @Test
    void settingFromAVariableThatHasNoValueFailsInsteadOfCopyingNothing() {
        Run r = run("""
                workflow Main() {
                    set copy = nothing_here
                }
                """, "Main");

        assertNotNull(r.error());
        assertTrue(r.error().getMessage().contains("nothing_here has no value"), r.error().getMessage());
    }

    // ---- is ok -----------------------------------------------------------------------------------------------------------------

    @Test
    void isOkIsTrueExactlyForTheOutcomesATaskCallsOk() {
        Run r = run("""
                workflow Main(why) {
                    run TestRun(outcome = "passed") -> res_a
                    run TestRun(outcome = "failed") -> res_b
                    run TestRun(outcome = "timed_out") -> res_c
                    run Fine() -> res_d
                    run Refuse() -> res_e
                    alt (res_a is ok) { note "good is ok" }
                    alt (res_b is ok) { note "bad is ok" } else { note "bad is not ok" }
                    alt (res_c is not ok) { note "hung is not ok" }
                    alt (res_d is ok) { note "fine is ok (the default word is ok)" }
                    alt (res_e is not ok) { note "refused is not ok" }
                    alt (why == "never") { run Fine() -> res_f }
                    alt (res_f is ok) { note "a step that never ran is ok?!" } else { note "skipped is not ok" }
                    alt (res_f is not ok) { note "and is not ok, which is the exact negation" }
                }
                """, "Main");

        assertNull(r.error());
        assertEquals(List.of("good is ok", "bad is not ok", "hung is not ok", "fine is ok (the default word is ok)", "refused is not ok",
                "skipped is not ok", "and is not ok, which is the exact negation"), r.notes());
    }

    @Test
    void aLoopCanRunUntilAStepIsOk() {
        Run r = run("""
                workflow Main() {
                    loop until (attempt is ok) max 5 {
                        run Flaky() -> attempt
                    }
                    note "ok after {attempt.outcome}"
                }
                """, "Main");

        assertNull(r.error());
        assertEquals(3, flakyCalls.get(), "ran until the third call, then stopped");
        assertEquals(List.of("ok after ok"), r.notes());
    }

    @Test
    void aSimulatedStepCountsAsOkSoAFreeMockRunReachesTheEnd() {
        String script = """
                workflow Main() {
                    run Writes() -> saved
                    alt (saved is ok) { note "carried on" } else { halt "would stop a mock run" }
                    alt (saved.outcome == "ok") { note "but the outcome word is not ok" } else { note "the outcome word is simulated" }
                }
                """;
        Run real = run(script, "Main", null, false);
        Run simulated = run(script, "Main", null, true);

        assertNull(real.error());
        assertEquals(List.of("carried on", "but the outcome word is not ok"), real.notes());
        assertNull(simulated.error());
        assertEquals(List.of("carried on", "the outcome word is simulated"), simulated.notes(), "== still means exactly what it says");
    }

    @Test
    void aRealRunNeverProducesASimulatedResult() {
        Run real = run("""
                workflow Main() {
                    run Writes() -> saved
                }
                """, "Main");

        assertNull(real.error());
        assertNotEquals("simulated", ((Map<?, ?>) real.variables().get("saved")).get("outcome"));
        assertNull(((Map<?, ?>) real.variables().get("saved")).get("simulated"));
    }

    // ---- the static checks -----------------------------------------------------------------------------------------------------

    private List<ScriptValidator.Problem> check(String script) {
        LoomScript parsed = new LoomParser(new Lexer(script).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(parsed, new ToolRegistry(), model -> null);
        executor.setTaskRegistry(tasks());
        return new ScriptValidator().validate(parsed, executor.validationContext());
    }

    @Test
    void isOkOnSomethingThatIsNotARunResultIsALoadErrorNamingTheLine() {
        List<ScriptValidator.Problem> problems = check("""
                agent A { model: "test"  system: "x" }
                workflow Main() {
                    delegate "hi" to A -> answer
                    alt (answer is ok) { note "?" }
                }
                """);

        assertTrue(problems.stream().anyMatch(p -> p.severity() == ScriptValidator.Severity.ERROR && p.line() == 4 && p.message().contains("is ok")), problems.toString());
    }

    @Test
    void aReturnInsideParallelIsRefusedAndAStatementAfterAHaltIsFlagged() {
        List<ScriptValidator.Problem> problems = check("""
                workflow Main() {
                    parallel {
                        return "a"
                        return "b"
                    }
                }
                workflow Other() {
                    halt "done"
                    note "unreachable"
                }
                """);

        assertTrue(problems.stream().anyMatch(p -> p.severity() == ScriptValidator.Severity.ERROR && p.message().contains("which branch")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.severity() == ScriptValidator.Severity.WARNING && p.line() == 9 && p.message().contains("never reached")), problems.toString());
    }

    @Test
    void theNewWordsStillWorkAsVariableNames() {
        Run r = run("""
                workflow Main() {
                    set ok = "yes"
                    set halt_reason = "nothing"
                    alt (ok == "yes") { note "a name works: {ok}" }
                }
                """, "Main");

        assertNull(r.error());
        assertEquals(List.of("a name works: yes"), r.notes());
    }

    @Test
    void anUnderscoreNameForSetIsRefusedAndHaltNeedsAReason() {
        assertThrows(RuntimeException.class, () -> new LoomParser(new Lexer("workflow Main() { set _x = 1 }").tokenize()).parseScript());
        assertThrows(RuntimeException.class, () -> new LoomParser(new Lexer("workflow Main() { halt }").tokenize()).parseScript());
    }
}
