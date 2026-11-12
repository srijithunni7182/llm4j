package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.ChildRuns;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guard G1 of phase 33 (D4): the parent is killed at every point between {@code start} and {@code
 * wait}, resumed, and there is exactly one child and every effect happened once.
 *
 * <p>A kill point is just before a journal write of the run tree (the parent's or the child's), and
 * after it every later write fails too, as it would in a dead process. The writes inside an effect,
 * between its pending marker and its done marker, are not kill points: a crash there leaves the
 * effect's outcome unknown, which is the effect rule's concern and not a duplicate child (D4 says
 * so).
 */
@Tag("slow")
class KillPointTest {

    private static final String SOURCE =
            """
            workflow Child(topic) {
                run Inside() -> inside
                return inside
            }
            workflow Main(q) {
                run Before() -> a
                start Child(topic = q) budget { tokens: 100 } -> work
                run Between() -> b
                wait work timeout 5m -> outcome
                run After() -> c
                return outcome
            }
            """;

    /**
     * The writes between an effect's pending marker and its done marker. A crash there leaves the
     * effect's outcome unknown, which the effect rule owns (D4: this guard is about the child, not
     * about a tool whose own effect was not journaled).
     */
    private static final Set<String> INSIDE_AN_EFFECT = Set.of("effect_run", "effect_done");

    /** The whole run tree's journals, kept across the "crash". */
    private static final class World {
        final Map<String, RunJournal> stores = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> effects = new ConcurrentHashMap<>();
        final Set<String> startedChildren = ConcurrentHashMap.newKeySet();
        int writes;
        int crashBefore = -1;
        boolean dead;

        RunJournal journal(String runId) {
            RunJournal base = stores.computeIfAbsent(runId, k -> RunJournal.inMemory());
            return new RunJournal() {
                @Override
                public Optional<Entry> get(String stepId) {
                    return base.get(stepId);
                }

                @Override
                public void put(String stepId, Entry entry) {
                    synchronized (World.this) {
                        if (dead) throw new Crash();
                        if (!INSIDE_AN_EFFECT.contains(entry.kind())) {
                            if (writes++ == crashBefore) {
                                dead = true;
                                throw new Crash();
                            }
                        }
                    }
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

        private TaskRegistry registry;

        TaskRegistry tasksOnce() {
            if (registry != null) return registry;
            TaskRegistry tasks = new TaskRegistry();
            for (String name : new String[] {"Before", "Between", "After", "Inside"}) {
                effects.put(name, new AtomicInteger());
                tasks.register(
                        Task.of(
                                name,
                                TaskEffect.CHANGES,
                                EffectPolicy.DEFAULT,
                                ctx -> {
                                    effects.get(name).incrementAndGet();
                                    return TaskResult.value(name);
                                }));
            }
            registry = tasks;
            return tasks;
        }
    }

    /** What a dead process leaves: nothing can be written, and nothing swallows it. */
    private static final class Crash extends Error {
        Crash() {
            super("simulated crash", null, false, false);
        }
    }

    private Object run(World world) {
        HarnessExecutor parent =
                new HarnessExecutor(
                        StartParseTest.parse(SOURCE), new ToolRegistry(), model -> null);
        parent.setTaskRegistry(world.tasksOnce());
        parent.setRunId("run-1");
        parent.setJournal(world.journal("run-1"));
        parent.setChildRuns(
                new ChildRuns() {
                    @Override
                    public RunJournal openJournal(String childRunId) {
                        return world.journal(childRunId);
                    }

                    @Override
                    public void record(String childRunId, String parentRunId, String workflow) {
                        world.startedChildren.add(childRunId);
                    }
                });
        parent.setChildScheduler(new ControlledChildScheduler());
        parent.initialize();
        parent.executeWorkflow("Main", Map.of("q", "x"));
        return parent.getContext().getVariable("outcome");
    }

    @Test
    void killingTheParentAtEveryPointLeavesOneChildAndEveryEffectOnce() {
        World baseline = new World();
        run(baseline);
        int writes = baseline.writes;
        assertThat(writes).as("a run of this shape writes some journal entries").isGreaterThan(8);

        for (int point = 0; point < writes; point++) {
            World world = new World();
            world.crashBefore = point;
            try {
                run(world);
            } catch (Crash | RuntimeException expected) {
                // the process died here; what survives is in the journals
            }
            world.dead = false;
            world.crashBefore = -1;

            Object outcome;
            try {
                outcome = run(world);
            } catch (RuntimeException e) {
                throw new AssertionError(
                        "the resumed run failed after a crash before write " + point + " of "
                                + writes + ", journal of run-1: "
                                + new TreeMap<>(world.stores.get("run-1").all()),
                        e);
            }

            String at = "after a crash before write " + point + " of " + writes;
            assertThat(outcome).as("the resumed run finishes " + at).isNotNull();
            assertThat(world.startedChildren).as("exactly one child " + at).hasSize(1);
            assertThat(world.stores.keySet()).as("one journal for the child " + at).hasSize(2);
            Map<String, Integer> counts = new TreeMap<>();
            world.effects.forEach((name, n) -> counts.put(name, n.get()));
            assertThat(counts)
                    .as("every effect once " + at)
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("Before", 1, "Between", 1, "After", 1, "Inside", 1));
        }
    }
}
