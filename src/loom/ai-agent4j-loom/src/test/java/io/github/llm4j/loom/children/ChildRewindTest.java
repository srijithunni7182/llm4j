package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.execution.ChildScheduler;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Guard G25 of phase 34 (F1): {@code cancel handle rewind to <checkpoint> at most N times} sends a
 * child back to a checkpoint it declared and lets it go on. The child goes into a new generation
 * (the old one stays as history), re-runs from the checkpoint, and is sent back at most N times. It
 * runs on the launcher's launch method and monitor (D12 step 1), so a child that is asleep and a
 * child that is running are both covered. Written before the code.
 */
class ChildRewindTest {

    private final ChildHarness h = new ChildHarness();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    /** A task that says how many times it has been called with this label, so a re-run shows. */
    private void counting() {
        h.task(
                "Count",
                ctx -> {
                    String label = String.valueOf(ctx.arg("label"));
                    return TaskResult.value(
                            String.valueOf(
                                    calls.computeIfAbsent(label, k -> new AtomicInteger())
                                            .incrementAndGet()));
                });
    }

    /** The child sleeps the first time it reaches b, so a parent can send it back while it is asleep. */
    private static final String NAPPER =
            """
            workflow Worker(x) {
                run Count(label = "a") -> a
                checkpoint collected
                run Count(label = "b") -> b
                alt (b.value == "1") { sleep 1h }
                return b
            }
            """;

    private static RunJournal durable(RunJournal base) {
        return new RunJournal() {
            @Override
            public java.util.Optional<Entry> get(String stepId) {
                return base.get(stepId);
            }

            @Override
            public void put(String stepId, Entry entry) {
                base.put(stepId, entry);
            }

            @Override
            public Map<String, Entry> all() {
                return base.all();
            }

            @Override
            public boolean isDurable() {
                return true;
            }
        };
    }

    private HarnessExecutor parent(String source, ChildScheduler scheduler) {
        counting();
        HarnessExecutor e = h.parent(source, scheduler);
        return e;
    }

    // ---- a child that is asleep ----

    @Test
    void aSleepingChildSentBackToACheckpointGoesOnInANewGenerationAndTheOldOneStays() {
        ControlledChildScheduler scheduler = new ControlledChildScheduler();
        h.task(
                "LetItNap",
                ctx -> {
                    scheduler.runNext(); // the child runs until it sleeps
                    return TaskResult.value("napping");
                });
        HarnessExecutor e =
                parent(
                        NAPPER
                                + """
                                workflow Main(q) {
                                    start Worker(x = q) budget { tokens: 10 } -> work
                                    run LetItNap() -> nap
                                    set before = work.status
                                    cancel work rewind to collected at most 2 times reason "try again"
                                    wait work timeout 5h -> outcome
                                    set after = work.status
                                }
                                """,
                        scheduler);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("before")).isEqualTo("sleeping");
        assertThat(e.getContext().getVariable("after")).isEqualTo("completed");
        assertThat(calls.get("a")).as("the step before the checkpoint is replayed, not run again").hasValue(1);
        assertThat(calls.get("b")).as("the step after it ran again").hasValue(2);
        Generations generations = new Generations(h.onlyChild());
        assertThat(generations.total()).isEqualTo(1);
        assertThat(generations.all().get(0).name()).isEqualTo("collected");
        assertThat(generations.all().get(0).generation()).isEqualTo(2);
        assertThat(generations.all().get(0).by()).isEqualTo("parent");
        assertThat(h.onlyChild().all().keySet())
                .as("the old generation stays as history")
                .anyMatch(k -> k.startsWith("Worker/s2") && !k.contains("~"))
                .anyMatch(k -> k.startsWith("Worker/s2~2"));
    }

    @Test
    void theChildIsSentBackAtMostAsManyTimesAsTheStatementSays() {
        ControlledChildScheduler scheduler = new ControlledChildScheduler();
        h.task(
                "LetItNap",
                ctx -> {
                    scheduler.runNext();
                    return TaskResult.value("napping");
                });
        HarnessExecutor e =
                parent(
                        """
                        workflow Worker(x) {
                            run Count(label = "a") -> a
                            checkpoint collected
                            run Count(label = "b") -> b
                            sleep 1h
                            return b
                        }
                        workflow Main(q) {
                            start Worker(x = q) budget { tokens: 10 } -> work
                            run LetItNap() -> nap
                            cancel work rewind to collected at most 1 times
                            run LetItNap() -> again
                            cancel work rewind to collected at most 1 times
                        }
                        """,
                        scheduler);

        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of("q", "x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at most 1")
                .hasMessageContaining("collected");
        assertThat(new Generations(h.onlyChild()).total()).as("the second rewind was refused").isEqualTo(1);
    }

    @Test
    void aSettledChildIsNotSentBackAndSayingSoIsARecordedNoOp() {
        HarnessExecutor e =
                parent(
                        """
                        workflow Worker(x) {
                            run Count(label = "a") -> a
                            checkpoint collected
                            return a
                        }
                        workflow Main(q) {
                            start Worker(x = q) budget { tokens: 10 } -> work
                            wait work timeout 1m -> done
                            cancel work rewind to collected at most 2 times
                            set seen = work.status
                        }
                        """,
                        new ControlledChildScheduler());

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("completed");
        assertThat(new Generations(h.onlyChild()).total()).isZero();
    }

    @Test
    void aReplayedCancelDoesNotSendTheChildBackAgain() {
        ControlledChildScheduler scheduler = new ControlledChildScheduler();
        h.task(
                "LetItNap",
                ctx -> {
                    scheduler.runNext();
                    return TaskResult.value("napping");
                });
        String main =
                NAPPER
                        + """
                        workflow Main(q) {
                            start Worker(x = q) budget { tokens: 10 } -> work
                            run LetItNap() -> nap
                            cancel work rewind to collected at most 2 times
                            wait work timeout 5h -> outcome
                        }
                        """;
        parent(main, scheduler).executeWorkflow("Main", Map.of("q", "x"));
        int after = new Generations(h.onlyChild()).total();

        // the parent is run again over the same journals: the cancel is already recorded
        HarnessExecutor replay = h.parent(main, new ControlledChildScheduler());
        replay.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(new Generations(h.onlyChild()).total()).isEqualTo(after);
    }

    // ---- a child that is running ----

    @Test
    void aRunningChildIsStoppedAtItsNextStepAndSentBackOnceItsEffectHasFinished() throws Exception {
        CountDownLatch inEffect = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holds = new AtomicInteger();
        h.task(
                "Hold",
                ctx -> {
                    holds.incrementAndGet();
                    inEffect.countDown();
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return TaskResult.value("held");
                });
        h.task(
                "Pump",
                ctx -> {
                    try {
                        inEffect.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return TaskResult.value("pumped");
                });
        h.task(
                "Release",
                ctx -> {
                    release.countDown();
                    return TaskResult.value("released");
                });
        HarnessExecutor e =
                parent(
                        """
                        workflow Worker(x) {
                            run Count(label = "a") -> a
                            checkpoint collected
                            run Hold() -> held
                            run Count(label = "b") -> b
                            return b
                        }
                        workflow Main(q) {
                            start Worker(x = q) budget { tokens: 10 } -> work
                            run Pump() -> pumped
                            cancel work rewind to collected at most 1 times
                            run Release() -> released
                            wait work timeout 1m -> outcome
                            set after = work.status
                        }
                        """,
                        ChildScheduler.threads());
        Thread parent = new Thread(() -> e.executeWorkflow("Main", Map.of("q", "x")));
        parent.setDaemon(true);
        parent.start();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (parent.isAlive() && System.nanoTime() < end) {
            h.clock.advance(Duration.ofSeconds(1)); // the wait counts virtual time
            parent.join(10);
        }

        assertThat(parent.isAlive()).isFalse();
        assertThat(e.getContext().getVariable("after")).isEqualTo("completed");
        assertThat(holds).as("the effect under way finished, then the step ran again in generation 2").hasValue(2);
        assertThat(calls.get("a")).hasValue(1);
        assertThat(new Generations(h.onlyChild()).total()).isEqualTo(1);
    }
}
