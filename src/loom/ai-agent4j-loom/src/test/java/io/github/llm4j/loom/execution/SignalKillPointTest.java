package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.children.StartParseTestAccess;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Guard G16 of phase 34 (D8, D15, principle 16): kill the whole run tree before every journal write
 * of an exchange of signals, resume it over the same journals, and check that each signal is
 * received exactly once, the child's effect happened once, the progress was reported once and the
 * answer is the same. It is exhaustive, so it is {@code slow}; the progress report, a wait that
 * suspends, a notify that wakes, a signal back to the parent and the parent's wait for it are all
 * in the run, so each of their writes is a kill point.
 */
@Tag("slow")
@Timeout(300)
class SignalKillPointTest {

    private static final String SOURCE =
            """
            workflow Child(x) progress { stage: string } {
                report { stage: "waiting" }
                wait signal "go" timeout 10m -> instruction
                run Work() -> done
                notify parent "found" "{instruction}"
                return instruction
            }
            workflow Main(q) {
                start Child(x = q) budget { tokens: 100 } -> work
                run LetItWait() -> kick
                notify work "go" "do it"
                wait work signal "found" timeout 5m -> got
                wait work timeout 5m -> out
                return got
            }
            """;

    private static final class Crash extends Error {
        Crash() {
            super("simulated crash", null, false, false);
        }
    }

    private static final class World {
        final Map<String, RunJournal> stores = new ConcurrentHashMap<>();
        final AtomicInteger work = new AtomicInteger();
        final Set<String> startedChildren = ConcurrentHashMap.newKeySet();
        int writes;
        final java.util.List<String> keys = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        int crashBefore = -1;
        boolean dead;

        RunJournal journal(String runId) {
            final String runId0 = runId;
            RunJournal base = stores.computeIfAbsent(runId, k -> RunJournal.inMemory());
            return new RunJournal() {
                @Override
                public Optional<Entry> get(String stepId) {
                    return base.get(stepId);
                }

                @Override
                public synchronized void put(String stepId, Entry entry) {
                    if (dead) throw new Crash();
                    if (writes == crashBefore) {
                        dead = true;
                        throw new Crash();
                    }
                    writes++;
                    keys.add(runId0 + " " + stepId);
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
    }

    private Object run(World world) {
        ControlledChildScheduler scheduler = new ControlledChildScheduler();
        TaskRegistry tasks = new TaskRegistry();
        tasks.register(Task.of("Work", TaskEffect.CHANGES, EffectPolicy.DEFAULT, ctx -> {
            world.work.incrementAndGet();
            return TaskResult.value("worked");
        }));
        tasks.register(Task.of("LetItWait", TaskEffect.NONE, EffectPolicy.DEFAULT, ctx -> {
            scheduler.runNext();
            return TaskResult.value("ran");
        }));
        HarnessExecutor parent = new HarnessExecutor(StartParseTestAccess.parse(SOURCE), new ToolRegistry(), model -> null);
        parent.setTaskRegistry(tasks);
        parent.setRunId("run-1");
        parent.setJournal(world.journal("run-1"));
        parent.setChildRuns(new ChildRuns() {
            @Override
            public RunJournal openJournal(String childRunId) {
                return world.journal(childRunId);
            }

            @Override
            public void record(String childRunId, String parentRunId, String workflow) {
                world.startedChildren.add(childRunId);
            }
        });
        parent.setChildScheduler(scheduler);
        parent.initialize();
        parent.executeWorkflow("Main", Map.of("q", "x"));
        return parent.getContext().getVariable("out") == null ? parent.getContext().getVariable("got") : parent.getContext().getVariable("got");
    }

    @Test
    void killingTheTreeBeforeEveryWriteOfASignalExchangeDeliversEachSignalOnceAndRepeatsNoEffect() {
        World baseline = new World();
        assertThat(run(baseline)).isEqualTo("do it");
        int writes = baseline.writes;
        assertThat(writes).as("the exchange writes enough to be worth killing between: " + baseline.keys).isGreaterThan(12);

        int skipped = 0;
        for (int point = 0; point < writes; point++) {
            // the writes between an effect's pending marker and its done marker are not kill points: a crash
            // there leaves the effect's outcome unknown, which is the effect rule's concern (as in KillPointTest)
            if (baseline.keys.get(point).contains("#effect")) {
                skipped++;
                continue;
            }
            World world = new World();
            world.crashBefore = point;
            try {
                run(world);
            } catch (Crash | RuntimeException expected) {
                // the process died here; what survives is in the journals
            }
            world.dead = false;
            world.crashBefore = -1;

            Object got;
            try {
                got = run(world);
            } catch (RuntimeException e) {
                throw new AssertionError("the resumed run failed after a crash before write " + point + " of " + writes
                        + ", child journal: " + new TreeMap<>(world.stores.entrySet().stream().filter(x -> !x.getKey().equals("run-1")).findFirst().map(Map.Entry::getValue).map(RunJournal::all).orElse(Map.of())), e);
            }

            String at = "after a crash before write " + point + " of " + writes;
            assertThat(got).as("the answer is the same " + at).isEqualTo("do it");
            assertThat(world.work).as("the child's effect happened once " + at).hasValue(1);
            assertThat(world.startedChildren).as("one child " + at).hasSize(1);
            RunJournal child = world.stores.entrySet().stream().filter(x -> !x.getKey().equals("run-1")).findFirst().orElseThrow().getValue();
            assertThat(Mailbox.all(child, Mailbox.Direction.IN)).as("the parent's signal was written once " + at).hasSize(1);
            assertThat(Mailbox.all(child, Mailbox.Direction.OUT)).as("the child's signal was written once " + at).hasSize(1);
            assertThat(Mailbox.pending(child, Mailbox.Direction.IN, "go")).as("it was read " + at).isZero();
            assertThat(Mailbox.pending(child, Mailbox.Direction.OUT, "found")).as("it was read " + at).isZero();
            assertThat(child.all().values().stream().filter(e -> "progress".equals(e.kind())).count()).as("one report " + at).isEqualTo(1);
            assertThat(child.all().values().stream().filter(e -> "signal-wait".equals(e.kind())).count()).as("one read " + at).isEqualTo(1);
        }
        assertThat(skipped).as("only the effect's own markers were left out").isLessThanOrEqualTo(4);
    }
}
