package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.ChildLimits;
import io.github.llm4j.loom.execution.ChildScheduler;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.ChildHandle;
import io.github.llm4j.loom.runtime.ChildStatus;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Phase 33d: {@code cancel}, and a parent that ends cancels its children (D10, D13; guards G3 and G7). */
class ChildCancelTest {

    private final Map<String, RunJournal> journals = new ConcurrentHashMap<>();
    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger after = new AtomicInteger();
    private volatile CountDownLatch inEffect = new CountDownLatch(0);
    private volatile CountDownLatch releaseEffect = new CountDownLatch(0);

    private HarnessExecutor executor(String source, ChildScheduler scheduler, ChildLimits limits) {
        TaskRegistry tasks = new TaskRegistry();
        tasks.register(
                Task.of(
                        "Inside",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            inside.incrementAndGet();
                            inEffect.countDown();
                            try {
                                releaseEffect.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return TaskResult.value("inside");
                        }));
        tasks.register(
                Task.of(
                        "After",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            after.incrementAndGet();
                            return TaskResult.value("after");
                        }));
        tasks.register(
                Task.of(
                        "Pump",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            try {
                                inEffect.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            return TaskResult.value("in effect");
                        }));
        tasks.register(
                Task.of(
                        "Release",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            releaseEffect.countDown();
                            return TaskResult.value("released");
                        }));
        tasks.register(
                Task.of(
                        "Explode",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            throw new IllegalStateException("boom");
                        }));
        HarnessExecutor e =
                new HarnessExecutor(StartParseTest.parse(source), new ToolRegistry(), model -> null);
        e.setTaskRegistry(tasks);
        e.setRunId("p");
        e.setJournal(journals.computeIfAbsent("p", k -> RunJournal.inMemory()));
        e.setChildRuns(id -> journals.computeIfAbsent(id, k -> RunJournal.inMemory()));
        e.setChildScheduler(scheduler);
        if (limits != null) e.setChildLimits(limits);
        e.initialize();
        return e;
    }

    private HarnessExecutor executor(String source) {
        return executor(source, new ControlledChildScheduler(), null);
    }

    private static final String CHILD =
            "workflow Child(x) { run Inside() -> a  run After() -> b  return b }\n";

    private String childJournalMarker() {
        return journals.entrySet().stream()
                .filter(e -> !e.getKey().equals("p"))
                .map(e -> e.getValue().get("#cancel"))
                .filter(java.util.Optional::isPresent)
                .map(o -> String.valueOf(o.get().value()))
                .findFirst()
                .orElse("");
    }

    @Test
    void aChildCancelledBeforeItRanNeverRunsAndTheParentReadsWhy() {
        HarnessExecutor e =
                executor(
                        CHILD
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    cancel work reason "not needed"
                                    wait work timeout 1m
                                    set seen = work.status
                                    set why = work.reason
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("cancelled");
        assertThat(e.getContext().getVariable("why")).isEqualTo("not needed");
        assertThat(inside).hasValue(0);
    }

    @Test
    void cancellingAChildThatAlreadySettledChangesNothing() {
        HarnessExecutor e =
                executor(
                        "workflow Quick(x) { run After() -> b  return b }\n"
                                + """
                                workflow Main(q) {
                                    start Quick(x = q) budget { tokens: 1 } -> work
                                    wait work timeout 1m -> out
                                    cancel work reason "too late"
                                    cancel work reason "again"
                                    set seen = work.status
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("completed");
        assertThat(after).hasValue(1);
    }

    @Test
    void aParentThatFinishesCancelsTheChildrenItNeverWaitedFor() {
        HarnessExecutor e =
                executor(
                        CHILD
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    note "done without waiting"
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(inside).as("the child never ran").hasValue(0);
        assertThat(childJournalMarker()).contains("parent ended");
    }

    @Test
    void aParentThatHaltsOrFailsCancelsItsChildrenToo() {
        HarnessExecutor halting =
                executor(
                        CHILD
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    halt "stop here"
                                }
                                """);
        assertThatThrownBy(() -> halting.executeWorkflow("Main", Map.of("q", "x")))
                .hasMessageContaining("stop here");
        assertThat(childJournalMarker()).contains("parent ended");
        assertThat(inside).hasValue(0);

        journals.clear();
        HarnessExecutor failing =
                executor(
                        CHILD
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    run Explode() -> boom
                                }
                                """);
        assertThatThrownBy(() -> failing.executeWorkflow("Main", Map.of("q", "x")))
                .hasMessageContaining("boom");
        assertThat(childJournalMarker()).contains("parent ended");
        assertThat(inside).hasValue(0);
    }

    @Tag("slow") // real threads: a child is held in the middle of an effect while its parent cancels it
    @Test
    @SuppressWarnings("unchecked")
    void aCancelNeverCutsAnEffectInHalfItFinishesIsJournaledAndThenTheChildStops() throws Exception {
        inEffect = new CountDownLatch(1);
        releaseEffect = new CountDownLatch(1);
        HarnessExecutor e =
                executor(
                        CHILD
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    run Pump() -> pumped
                                    cancel work use partial reason "enough"
                                    run Release() -> released
                                    wait work timeout 10s
                                    set seen = work.status
                                    set why = work.reason
                                }
                                """,
                        ChildScheduler.threads(),
                        null);
        HarnessExecutor parent = e;

        parent.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(parent.getContext().getVariable("seen")).isEqualTo("cancelled");
        assertThat(parent.getContext().getVariable("why")).isEqualTo("enough");
        ChildHandle cancelled = (ChildHandle) parent.getContext().getVariable("work");
        assertThat((Map<String, Object>) cancelled.fact("partial"))
                .as("use partial keeps what the child had produced")
                .containsKey("a");
        assertThat(inside).as("the effect ran once and was allowed to finish").hasValue(1);
        assertThat(after).as("the step after it never ran").hasValue(0);
        String childId =
                journals.keySet().stream().filter(k -> !k.equals("p")).findFirst().orElseThrow();
        assertThat(journals.get(childId).all().values())
                .as("the finished effect is in the child's journal")
                .anyMatch(entry -> "task".equals(entry.kind()));
    }

    @Tag("slow") // real threads and a short real grace period
    @Test
    void aChildThatDoesNotSettleWithinTheGraceIsRecordedAsCancelledWithANote() throws Exception {
        inEffect = new CountDownLatch(1);
        releaseEffect = new CountDownLatch(1); // never released during the test
        HarnessExecutor e =
                executor(
                        CHILD
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    run Pump() -> pumped
                                }
                                """,
                        ChildScheduler.threads(),
                        new ChildLimits(32, 8, Duration.ofMillis(100)));
        HarnessExecutor parent = e;

        parent.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(childJournalMarker()).contains("parent ended");
        ChildHandle held = (ChildHandle) parent.getContext().getVariable("work");
        assertThat(held.status()).isEqualTo(ChildStatus.CANCELLED);
        assertThat(String.valueOf(held.fact("reason"))).contains("did not settle within the grace period");
        releaseEffect.countDown(); // let the held thread go
    }
}
