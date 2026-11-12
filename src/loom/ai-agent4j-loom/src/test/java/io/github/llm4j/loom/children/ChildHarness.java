package io.github.llm4j.loom.children;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.ChildRuns;
import io.github.llm4j.loom.execution.ChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.ChildStatus;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * What the phase 34 tests of the launcher share: a parent over in-memory journals, a clock the test
 * moves, a task registry the test adds to, and a record of what the host was told about each
 * child. Nothing here sleeps or races: a test chooses the scheduler.
 */
final class ChildHarness {

    /** A clock that moves only when the test says so. */
    static final class MovingClock extends java.time.Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    final MovingClock clock = new MovingClock();
    final Map<String, RunJournal> journals = new ConcurrentHashMap<>();
    final List<String> settled = Collections.synchronizedList(new ArrayList<>());
    final Map<String, String> parents = new ConcurrentHashMap<>();
    final TaskRegistry tasks = new TaskRegistry();
    HarnessExecutor parent;

    ChildHarness() {
        task("Fine", ctx -> TaskResult.value("ok"));
        task(
                "Explode",
                ctx -> {
                    throw new IllegalStateException("the source is down");
                });
    }

    /** Adds a task that changes nothing, so it is never held back by an effect policy. */
    ChildHarness task(String name, Function<io.github.llm4j.agent.task.TaskContext, TaskResult> body) {
        tasks.register(Task.of(name, TaskEffect.NONE, EffectPolicy.DEFAULT, body::apply));
        return this;
    }

    /** A parent run named {@code p} over {@code source}, its children on {@code scheduler}. */
    HarnessExecutor parent(String source, ChildScheduler scheduler) {
        parent =
                new HarnessExecutor(
                        StartParseTest.parse(source),
                        new ToolRegistry(),
                        new io.github.llm4j.loom.eval.MockModels());
        parent.setTaskRegistry(tasks);
        parent.setClock(clock);
        parent.setSuperviseTick(Duration.ZERO);
        parent.setRunId("p");
        parent.setJournal(journals.computeIfAbsent("p", k -> RunJournal.inMemory()));
        parent.setChildRuns(
                new ChildRuns() {
                    @Override
                    public RunJournal openJournal(String childRunId) {
                        return journals.computeIfAbsent(childRunId, k -> RunJournal.inMemory());
                    }

                    @Override
                    public void record(String childRunId, String parentRunId, String workflow) {
                        parents.put(childRunId, parentRunId);
                    }

                    @Override
                    public void settled(String childRunId, ChildStatus status) {
                        settled.add(status.word());
                    }
                });
        parent.setChildScheduler(scheduler);
        parent.initialize();
        return parent;
    }

    /** The journal of the one child this parent started. */
    RunJournal onlyChild() {
        return journals.entrySet().stream()
                .filter(e -> !e.getKey().equals("p"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }
}
