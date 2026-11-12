package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.ChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guard G14 of phase 33 (D29): a parent that waits keeps its children running, and a parent
 * suspended for anything else, a question to a person for instance, pauses them. Real threads and
 * latches, no sleeping: a child is held inside an effect while its parent suspends.
 */
@Tag("slow")
class ChildPauseTest {

    private static RunJournal durable(RunJournal base) {
        return new RunJournal() {
            @Override
            public Optional<Entry> get(String stepId) {
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

    @Test
    void aParentSuspendedForAPersonPausesItsRunningChildrenAtTheirNextStep() throws Exception {
        CountDownLatch inEffect = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger after = new AtomicInteger();
        Map<String, RunJournal> stores = new ConcurrentHashMap<>();
        TaskRegistry tasks = new TaskRegistry();
        tasks.register(
                Task.of(
                        "Inside",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            inEffect.countDown();
                            try {
                                release.await(10, TimeUnit.SECONDS);
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
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return TaskResult.value("pumped");
                        }));
        HarnessExecutor parent =
                new HarnessExecutor(
                        StartParseTest.parse(
                                """
                                workflow Child(x) { run Inside() -> a  run After() -> b  return b }
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1 } -> work
                                    run Pump() -> pumped
                                    human_prompt "Carry on?" -> answer
                                    wait work timeout 1m -> out
                                }
                                """),
                        new ToolRegistry(),
                        model -> null);
        parent.setTaskRegistry(tasks);
        parent.setHumanInterface(
                new io.github.llm4j.loom.runtime.HumanInterface() {
                    @Override
                    public String promptHuman(String message) {
                        throw new IllegalStateException("only the step form is used");
                    }

                    @Override
                    public String promptHuman(String stepId, String message, Hints hints) {
                        throw new RunSuspended(stepId, message);
                    }
                });
        parent.setRunId("p");
        parent.setJournal(durable(stores.computeIfAbsent("p", k -> RunJournal.inMemory())));
        parent.setChildRuns(id -> durable(stores.computeIfAbsent(id, k -> RunJournal.inMemory())));
        parent.setChildScheduler(ChildScheduler.threads());
        parent.initialize();

        assertThatThrownBy(() -> parent.executeWorkflow("Main", Map.of("q", "x")))
                .isInstanceOf(RunSuspended.class);

        release.countDown(); // the child's effect finishes, and it reaches its next step
        String childId = stores.keySet().stream().filter(k -> !k.equals("p")).findFirst().orElseThrow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (stores.get(childId).all().values().stream().noneMatch(e -> "task".equals(e.kind()))
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(stores.get(childId).all().values())
                .as("the effect under way finished and was journaled")
                .anyMatch(e -> "task".equals(e.kind()));
        assertThat(after).as("the child did not go on to its next step while its parent was suspended").hasValue(0);
    }
}
