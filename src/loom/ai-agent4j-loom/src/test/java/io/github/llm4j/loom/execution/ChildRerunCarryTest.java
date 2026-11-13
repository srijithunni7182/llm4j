package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.children.ChildHarness;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Guard G35 of phase 34 (D12 rule 7): a child that is run again over its journal keeps its
 * trajectory and what its supervisor forbade it. A replay emits no events for the steps it skips,
 * so a new executor that started empty would forget what the child had called, and would let it
 * call what it was forbidden. Written before the carry.
 */
class ChildRerunCarryTest {

    private final ChildHarness h = new ChildHarness();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    private ControlledChildScheduler scheduler;

    private HarnessExecutor parent(String source) {
        scheduler = new ControlledChildScheduler();
        h.task(
                "Count",
                ctx ->
                        TaskResult.value(
                                String.valueOf(
                                        calls.computeIfAbsent(String.valueOf(ctx.arg("label")), k -> new AtomicInteger())
                                                .incrementAndGet())));
        h.task(
                "Bad",
                ctx -> TaskResult.value("should not have run"));
        h.task(
                "LetItNap",
                ctx -> {
                    scheduler.runNext(); // the child runs until it sleeps
                    return TaskResult.value("napping");
                });
        return h.parent(source, scheduler);
    }

    private static final String WORKER =
            """
            workflow Worker(x) {
                run Count(label = "a") -> a
                checkpoint collected
                run Count(label = "b") -> b
                alt (b.value == "1") { sleep 1h }
                alt (b.value == "2") { run Bad() -> bad }
                return b
            }
            """;

    @Test
    void aRerunChildKeepsTheCallsItMadeBeforeAndAddsTheOnesItMakesNow() {
        HarnessExecutor e =
                parent(
                        WORKER
                                + """
                                workflow Main(q) {
                                    start Worker(x = q) budget { tokens: 10 } -> work
                                    run LetItNap() -> nap
                                    cancel work rewind to collected at most 2 times
                                    wait work timeout 5h -> out
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(h.attempts).as("the child ran twice: once, and again after the rewind").hasSize(2);
        Trajectory.Snapshot first = h.attempts.get(0).trajectory();
        Trajectory.Snapshot second = h.attempts.get(1).trajectory();
        assertThat(first.callsOf("Count")).isEqualTo(2); // a and b
        // step a is replayed (it emits nothing), b runs again: 2 carried + 1 new
        assertThat(second.callsOf("Count")).isEqualTo(3);
        assertThat(second.reached()).contains("collected");
        assertThat(second.firstCallOrder()).startsWith("Count");
    }

    @Test
    void whatTheSupervisorForbadeTheChildStaysForbiddenAfterItIsRunAgain() {
        HarnessExecutor e =
                parent(
                        WORKER
                                + """
                                workflow Main(q) {
                                    start Worker(x = q) budget { tokens: 10 } -> work
                                    supervise work { assert never calls Bad { cancel } }
                                    run LetItNap() -> nap
                                    cancel work rewind to collected at most 2 times
                                    wait work timeout 5h -> out
                                    set seen = work.status
                                    set why = work.failure
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("failed");
        assertThat(String.valueOf(e.getContext().getVariable("why"))).contains("forbids");
        assertThat(h.attempts.get(1).trajectory().refusedOf("Bad")).isEqualTo(1);
    }
}
