package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.execution.ChildRuns;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.runtime.ChildStatus;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Review findings on the launcher (phase 34 review): a child that cannot be run again after its
 * signal ends as failed instead of hanging in a claimed state, and a parent's wait keeps the
 * deadline it first computed, so a restart never lengthens it.
 */
class ChildWakeAndDeadlineTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    // ---- a launch that fails after the wake claimed the child ----

    @Test
    void aChildThatCannotBeRunAgainAfterItsSignalEndsFailedAndTheParentSeesItAtOnce() {
        ChildHarness h = new ChildHarness();
        ControlledChildScheduler scheduler = h.controlled();
        h.task(
                "LetItWait",
                ctx -> {
                    scheduler.runNext();
                    return TaskResult.value("ran");
                });
        HarnessExecutor parent =
                h.parent(
                        """
                        workflow Child(x) { wait signal "go" timeout 10m -> v  return "done" }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            run LetItWait() -> kick
                            notify work "go" "now"
                            wait work timeout 1m -> out
                            set seen = work.status
                            set why = work.failure
                        }
                        """,
                        scheduler);
        AtomicInteger prepared = new AtomicInteger();
        parent.setChildRuns(
                new ChildRuns() {
                    @Override
                    public RunJournal openJournal(String childRunId) {
                        return h.journals.computeIfAbsent(childRunId, k -> RunJournal.inMemory());
                    }

                    @Override
                    public void prepared(String childRunId, HarnessExecutor child) {
                        if (prepared.incrementAndGet() > 1) throw new IllegalStateException("the host refused the second run");
                    }

                    @Override
                    public void settled(String childRunId, ChildStatus status) {
                        h.settled.add(status.word());
                    }
                });

        parent.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(parent.getContext().getVariable("seen")).isEqualTo("failed");
        assertThat(String.valueOf(parent.getContext().getVariable("why"))).contains("could not be run again").contains("the host refused");
        assertThat(h.settled).as("the host is told the child ended").contains("failed");
        assertThat(Duration.between(START, h.clock.instant())).as("no timeout had to pass").isLessThan(Duration.ofSeconds(30));
    }

    // ---- the deadline of a wait is written once ----

    private static final String WAITS_FOR_EVER = "workflow Child(x) { wait signal \"never\" timeout 1h -> v  return \"done\" }\n";

    private static String deadlineKey(ChildHarness h) {
        return h.journals.get("p").all().keySet().stream().filter(k -> k.endsWith("#deadline")).findFirst().orElseThrow();
    }

    private HarnessExecutor run(ChildHarness h, String body) {
        return run(h, "work", body);
    }

    private HarnessExecutor run(ChildHarness h, String handle, String body) {
        ControlledChildScheduler scheduler = h.controlled();
        h.task(
                "LetItWait",
                ctx -> {
                    scheduler.runNext();
                    return TaskResult.value("ran");
                });
        HarnessExecutor parent =
                h.parent(
                        WAITS_FOR_EVER + "workflow Main(q) {\nstart Child(x = q) budget { tokens: 10 } -> " + handle + "\nrun LetItWait() -> kick\n" + body + "\n}\n",
                        scheduler);
        parent.executeWorkflow("Main", Map.of("q", "x"));
        return parent;
    }

    private static Duration elapsed(ChildHarness h) {
        return Duration.between(START, h.clock.instant());
    }

    @Test
    void aWaitWritesItsDeadlineOnceFromWhenItFirstRan() {
        ChildHarness h = new ChildHarness();
        run(h, "wait work timeout 10m -> out");

        Map<?, ?> value = (Map<?, ?>) h.journals.get("p").get(deadlineKey(h)).orElseThrow().value();
        assertThat(((Number) value.get("at")).longValue()).isEqualTo(START.plus(Duration.ofMinutes(10)).toEpochMilli());
    }

    @Test
    void aWaitThatRunsAgainKeepsTheDeadlineItWroteInsteadOfStartingTheTimeoutOver() {
        ChildHarness first = new ChildHarness();
        run(first, "wait work timeout 10m -> out");
        String key = deadlineKey(first);

        ChildHarness again = new ChildHarness();
        // the parent was killed after it wrote its deadline (one minute from the start) and before it recorded the outcome
        again.journals.put("p", RunJournal.inMemory());
        again.journals.get("p").put(key, new RunJournal.Entry("signal-deadline", Map.of("at", START.plus(Duration.ofMinutes(1)).toEpochMilli())));
        run(again, "wait work timeout 10m -> out");

        assertThat(elapsed(again)).as("it gave up at the first deadline, not ten minutes after the restart").isLessThan(Duration.ofMinutes(2));
    }

    @Test
    void aWaitAnyThatRunsAgainKeepsItsDeadlineToo() {
        ChildHarness first = new ChildHarness();
        run(first, "work[\"a\"]", "wait any work timeout 10m -> event");
        String key = deadlineKey(first);

        ChildHarness again = new ChildHarness();
        again.journals.put("p", RunJournal.inMemory());
        again.journals.get("p").put(key, new RunJournal.Entry("signal-deadline", Map.of("at", START.plus(Duration.ofMinutes(1)).toEpochMilli())));
        HarnessExecutor parent = run(again, "work[\"a\"]", "wait any work timeout 10m -> event\nset kind = event.kind");

        assertThat(elapsed(again)).isLessThan(Duration.ofMinutes(2));
        assertThat(parent.getContext().getVariable("kind")).isEqualTo("timeout");
    }

    @Test
    void withNoEarlierDeadlineTheWholeTimeoutIsWaited() {
        ChildHarness h = new ChildHarness();
        HarnessExecutor parent = run(h, "work[\"a\"]", "wait any work timeout 10m -> event\nset kind = event.kind");

        assertThat(parent.getContext().getVariable("kind")).isEqualTo("timeout");
        assertThat(elapsed(h)).isGreaterThanOrEqualTo(Duration.ofMinutes(10));
    }

    // ---- a child that dies with an Error is gone, and the parent hears of it ----

    @Test
    void aChildThatDiesWithAnErrorIsNotLeftLookingAliveAndTheParentIsNotHeldToItsTimeout() {
        ChildHarness h = new ChildHarness();
        h.task(
                "Boom",
                ctx -> {
                    throw new AssertionError("the child's thread died");
                });
        HarnessExecutor parent =
                h.parent(
                        """
                        workflow Child(x) { run Boom() -> r  return r }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            wait work timeout 5m -> out
                            set seen = work.status
                        }
                        """,
                        io.github.llm4j.loom.execution.ChildScheduler.threads());

        // the clock is frozen, so a wait that is not woken would never reach its timeout
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(20), () -> parent.executeWorkflow("Main", Map.of("q", "x")));

        assertThat(parent.getContext().getVariable("seen")).isEqualTo("failed");
    }
}
