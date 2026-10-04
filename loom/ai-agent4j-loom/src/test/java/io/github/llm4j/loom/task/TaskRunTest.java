package io.github.llm4j.loom.task;

import static io.github.llm4j.loom.task.TaskHarness.map;
import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.TraceEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskRunTest {

    private final TaskHarness h = new TaskHarness();

    @Test
    void noModelCalls() {
        h.pure("Amount", c -> TaskResult.value(40));
        h.pure("Double", c -> TaskResult.value(c.requireArg("n", Integer.class) * 2));
        HarnessExecutor e = h.run("""
                workflow Main() {
                    run Amount() -> a
                    run Double(n = a.value) -> b
                }
                """);
        assertEquals(0, h.modelCalls.get());
        assertEquals(80, map(e.getContext().getVariable("b")).get("value"));
    }

    @Test
    void mixedWorkflowCallsTheModelOnlyForAgentSteps() {
        h.pure("Check", c -> TaskResult.outcome("approved"));
        h.answers(TaskHarness.done("first"), TaskHarness.done("second"));
        HarnessExecutor e = h.run("""
                agent Writer { model: "m" system: "You are Writer." }
                workflow Main() {
                    delegate "draft" to Writer -> d1
                    run Check() -> v1
                    run Check() -> v2
                    delegate "polish" to Writer -> d2
                    run Check() -> v3
                }
                """);
        assertEquals(2, h.modelCalls.get(), "one call per agent step, none for the three task steps");
        assertEquals("approved", map(e.getContext().getVariable("v3")).get("outcome"));
    }

    @Test
    void typedArguments() {
        h.pure("Source", c -> TaskResult.ok(Map.of("amount", 40, "price", 12.5, "ids", List.of(1, 2), "meta", Map.of("k", "v"), "flag", true))
                .withValue("seven"));
        TaskHarness.Probe p = h.pure("Sink", c -> TaskResult.ok());
        h.run("""
                workflow Main() {
                    run Source() -> s
                    run Sink(amount = s.amount, price = s.price, ids = s.ids, meta = s.meta, flag = s.flag,
                             text = "Refund {s.amount} for {s.value}", lit = 42, dec = 3.5, yes = true, no = false, neg = -2,
                             whole = s) -> out
                }
                """);
        Map<String, Object> args = p.contexts.get(0).args();
        assertEquals(40, args.get("amount"));
        assertInstanceOf(Integer.class, args.get("amount"), "a number stays a number");
        assertEquals(12.5, args.get("price"));
        assertEquals(List.of(1, 2), args.get("ids"));
        assertEquals(Map.of("k", "v"), args.get("meta"));
        assertEquals(true, args.get("flag"));
        assertEquals("Refund 40 for seven", args.get("text"));
        assertEquals(42L, args.get("lit"));
        assertEquals(3.5, args.get("dec"));
        assertEquals(true, args.get("yes"));
        assertEquals(false, args.get("no"));
        assertEquals(-2L, args.get("neg"));
        assertEquals("ok", map(args.get("whole")).get("outcome"), "a whole variable is passed as itself");
        assertEquals(List.of("amount", "price", "ids", "meta", "flag", "text", "lit", "dec", "yes", "no", "neg", "whole"),
                new ArrayList<>(args.keySet()), "in the order written");
    }

    @Test
    void workflowInputsAreVisibleAsVariables() {
        TaskHarness.Probe p = h.pure("Echo", c -> TaskResult.value(c.variable("msg")));
        HarnessExecutor e = h.run("workflow Main(msg) { run Echo(m = msg) -> out }", "Main", new HashMap<>(Map.of("msg", "hello")));
        assertEquals("hello", p.contexts.get(0).args().get("m"));
        assertEquals("hello", p.contexts.get(0).variable("msg"), "the whole variable snapshot is readable");
        assertEquals("hello", map(e.getContext().getVariable("out")).get("value"));
    }

    @Test
    void resultMapBound() {
        h.pure("Policy", c -> TaskResult.rejected("over limit").with("max", 25));
        TaskHarness.Probe p = h.pure("Sink", c -> TaskResult.ok());
        HarnessExecutor e = h.run("""
                workflow Main() {
                    run Policy() -> verdict
                    run Sink(why = "{verdict.reason} (max {verdict.max})") -> out
                }
                """);
        assertEquals(Map.of("outcome", "rejected", "reason", "over limit", "max", 25), e.getContext().getVariable("verdict"));
        assertEquals("over limit (max 25)", p.contexts.get(0).args().get("why"));
    }

    @Test
    void altOnOutcome() {
        h.pure("Policy", c -> c.requireArg("amount", Integer.class) <= 50 ? TaskResult.outcome("approved") : TaskResult.rejected("over limit"));
        TaskHarness.Probe issue = h.changes("Issue", c -> TaskResult.ok());
        TaskHarness.Probe escalate = h.pure("Escalate", c -> TaskResult.ok());
        String script = """
                workflow Main() {
                    run Policy(amount = %d) -> verdict
                    alt (verdict.outcome == "approved") {
                        run Issue(amount = %d) -> receipt
                    } else {
                        run Escalate(reason = verdict.reason) -> ticket
                    }
                }
                """;
        h.run(script.formatted(40, 40));
        assertEquals(1, issue.calls.get());
        assertEquals(0, escalate.calls.get());
        h.journal = io.github.llm4j.loom.runtime.RunJournal.inMemory(); // a fresh run
        h.run(script.formatted(90, 90));
        assertEquals(1, issue.calls.get(), "the refund is not issued for 90");
        assertEquals(1, escalate.calls.get());
        assertEquals("over limit", escalate.contexts.get(0).args().get("reason"));
    }

    @Test
    void missingArgumentFailsClosed() {
        TaskHarness.Probe p = h.pure("T", c -> TaskResult.ok());
        RuntimeException e = assertThrows(RuntimeException.class, () -> h.run("""
                workflow Main() { run T(a = nothing.here) -> r retry 3 }
                """));
        assertTrue(e.getMessage().contains("nothing.here") && e.getMessage().contains("no value"), e.getMessage());
        assertEquals(0, p.calls.get(), "not invoked, and never retried");
        assertTrue(h.sleeps.isEmpty());

        // a plain name that was never set reads as "" elsewhere in Loom; a task must not be handed that
        for (String reference : new String[] {"never_set", "never_set.field", "x.y.z"}) {
            RuntimeException plain = assertThrows(RuntimeException.class,
                    () -> h.run("workflow Main() { run T(a = " + reference + ") -> r }"), reference);
            assertTrue(plain.getMessage().contains("no value"), plain.getMessage());
        }
        assertEquals(0, p.calls.get());

        // with on_failure the run goes on, with the reason in _error
        TaskHarness.Probe after = h.pure("After", c -> TaskResult.value(c.requireArg("why", String.class)));
        HarnessExecutor ok = h.run("""
                workflow Main() {
                    run T(a = nothing.here) -> r on_failure { run After(why = "{_error}") -> handled }
                }
                """);
        assertEquals(0, p.calls.get());
        assertEquals(1, after.calls.get());
        assertTrue(String.valueOf(map(ok.getContext().getVariable("handled")).get("value")).contains("nothing.here"));
    }

    @Test
    void immutableInputs() {
        Map<String, Object> seen = new HashMap<>();
        h.pure("Source", c -> TaskResult.ok(Map.of("list", List.of("a"), "map", Map.of("k", "v"))));
        h.pure("Mutator", c -> {
            for (Runnable attempt : List.<Runnable>of(
                    () -> c.args().put("x", 1),
                    () -> c.variables().put("x", 1),
                    () -> ((Map<String, Object>) c.arg("m")).put("x", 1),
                    () -> ((List<Object>) c.arg("l")).add("b"),
                    () -> ((Map<String, Object>) c.variable("src.map")).clear())) {
                try {
                    attempt.run();
                    seen.put("mutated" + seen.size(), true);
                } catch (UnsupportedOperationException expected) {
                    seen.put("blocked" + seen.size(), true);
                }
            }
            return TaskResult.ok();
        });
        HarnessExecutor e = h.run("""
                workflow Main() {
                    run Source() -> src
                    run Mutator(m = src.map, l = src.list) -> out
                }
                """);
        assertEquals(5, seen.keySet().stream().filter(k -> k.startsWith("blocked")).count(), seen.toString());
        assertEquals(Map.of("outcome", "ok", "list", List.of("a"), "map", Map.of("k", "v")), e.getContext().getVariable("src"));
    }

    @Test
    void resultIsCopiedSoLaterMutationDoesNotReachTheWorkflow() {
        List<Object> shared = new ArrayList<>(List.of(1));
        h.pure("Leaky", c -> TaskResult.ok().with("items", shared));
        HarnessExecutor e = h.run("workflow Main() { run Leaky() -> r }");
        shared.add(2);
        assertEquals(List.of(1), map(e.getContext().getVariable("r")).get("items"));
    }

    @Test
    void dynamicResultVariableInForEach() {
        h.pure("Echo", c -> TaskResult.value(c.requireArg("id", String.class)));
        HarnessExecutor e = h.executor("""
                workflow Main() {
                    run Echo(id = "x") -> items
                    for each item in things { run Echo(id = item.id) -> {item.id} }
                }
                """);
        e.initialize();
        e.getContext().setVariable("things", List.of(Map.of("id", "a"), Map.of("id", "b")));
        e.executeWorkflow("Main", new HashMap<>());
        assertEquals("a", map(e.getContext().getVariable("a")).get("value"));
        assertEquals("b", map(e.getContext().getVariable("b")).get("value"));
    }

    @Test
    void taskReturningNullFailsTheStep() {
        h.pure("Nothing", c -> null);
        RuntimeException e = assertThrows(RuntimeException.class, () -> h.run("workflow Main() { run Nothing() -> r }"));
        assertTrue(e.getMessage().contains("returned no result"), e.getMessage());
    }

    @Test
    void traceEvents() {
        h.pure("Policy", c -> TaskResult.outcome("approved"));
        h.run("workflow Main() { run Policy(amount = 5) -> v }");
        List<String> types = h.traceTypes().stream().filter(t -> t.startsWith("task")).toList();
        assertEquals(List.of(TraceEvent.TASK_START, TraceEvent.TASK_END), types);
        TraceEvent start = h.traceOf(TraceEvent.TASK_START).get(0);
        TraceEvent end = h.traceOf(TraceEvent.TASK_END).get(0);
        assertEquals("Policy", start.data().get("task"));
        assertEquals("none", start.data().get("effect"));
        assertNull(start.agent(), "a task has no agent");
        assertEquals("approved", end.data().get("outcome"));
        assertTrue(end.step().contains("/s0"), end.step());
        assertTrue(h.audit.stream().anyMatch(a -> a.startsWith("task_run") && a.contains("Policy") && a.contains("approved")), h.audit.toString());
    }

    @Test
    void auditMasksPii() {
        h.pure("Notify", c -> TaskResult.ok());
        h.run("workflow Main() { run Notify(to = \"ada.lovelace@example.com\") -> r }");
        String everything = h.audit + " " + h.trace.stream().map(t -> t.text() + t.data()).toList();
        assertFalse(everything.contains("ada.lovelace@example.com"), everything);
        assertTrue(h.traceOf(TraceEvent.TASK_START).get(0).data().get("args").toString().length() > 0);
    }
}
