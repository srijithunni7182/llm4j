package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.ChildRuns;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.ChildHandle;
import io.github.llm4j.loom.runtime.ChildStatus;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Phase 33b: {@code start} and {@code wait} on the controlled scheduler (decisions D8, D9). */
class StartWaitEngineTest {

    private final Map<String, RunJournal> journals = new ConcurrentHashMap<>();
    private final Map<String, String> parents = new ConcurrentHashMap<>();

    private HarnessExecutor run(String source) {
        TaskRegistry tasks = new TaskRegistry();
        tasks.register(
                Task.of(
                        "Fine",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> TaskResult.value("found it")));
        tasks.register(
                Task.of(
                        "Explode",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            throw new IllegalStateException("the source is down");
                        }));
        HarnessExecutor parent =
                new HarnessExecutor(StartParseTest.parse(source), new ToolRegistry(), model -> null);
        parent.setTaskRegistry(tasks);
        parent.setRunId("parent");
        parent.setJournal(journals.computeIfAbsent("parent", k -> RunJournal.inMemory()));
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
                });
        parent.setChildScheduler(new ControlledChildScheduler());
        parent.initialize();
        parent.executeWorkflow("Main", Map.of("q", "x"));
        return parent;
    }

    private static final String CHILDREN =
            """
            workflow Good(topic) { run Fine() -> r  return r }
            workflow Bad(topic) { run Explode() -> r  return r }
            """;

    @Test
    void aCompletedChildsResultComesBackThroughWaitAndTheHandleIsOk() {
        HarnessExecutor e =
                run(
                        CHILDREN
                                + """
                                workflow Main(q) {
                                    start Good(topic = q) budget { tokens: 10 } -> work
                                    wait work timeout 1m -> outcome
                                    alt (work is ok) { set verdict = "ok" } else { set verdict = "not ok" }
                                    set seen = work.status
                                }
                                """);

        assertThat(String.valueOf(e.getContext().getVariable("outcome"))).contains("found it");
        assertThat(e.getContext().getVariable("verdict")).isEqualTo("ok");
        assertThat(e.getContext().getVariable("seen")).isEqualTo("completed");
        assertThat(parents).containsValue("parent");
        assertThat(parents).hasSize(1);
    }

    @Test
    void aChildThatFailedIsAValueNotAnExceptionInTheParent() {
        HarnessExecutor e =
                run(
                        CHILDREN
                                + """
                                workflow Main(q) {
                                    start Bad(topic = q) budget { tokens: 10 } -> work
                                    wait work timeout 1m -> outcome on_failure { set handled = "yes" }
                                    alt (work is ok) { set verdict = "ok" } else { set verdict = "not ok" }
                                    set seen = work.status
                                    set why = work.failure
                                }
                                """);

        assertThat(String.valueOf(e.getContext().getVariable("outcome"))).isIn("", "null");
        assertThat(e.getContext().getVariable("verdict")).isEqualTo("not ok");
        assertThat(e.getContext().getVariable("seen")).isEqualTo("failed");
        assertThat(e.getContext().getVariable("handled")).isEqualTo("yes");
        assertThat(String.valueOf(e.getContext().getVariable("why"))).contains("the source is down");
    }

    @Test
    void readingTheResultOfAChildThatDidNotCompleteIsAnErrorThatNamesTheStatus() {
        assertThatThrownBy(
                        () ->
                                run(
                                        CHILDREN
                                                + """
                                                workflow Main(q) {
                                                    start Bad(topic = q) budget { tokens: 10 } -> work
                                                    wait work timeout 1m
                                                    set oops = work.result
                                                }
                                                """))
                .hasMessageContaining("not available")
                .hasMessageContaining("failed");
    }

    @Test
    void waitAllRecordsEveryOutcomeIncludingTheFailedOne() {
        HarnessExecutor e =
                run(
                        CHILDREN
                                + """
                                workflow Main(q) {
                                    start Good(topic = q) budget { tokens: 10 } -> work["a"]
                                    start Bad(topic = q) budget { tokens: 10 } -> work["b"]
                                    wait all work timeout 1m -> results
                                    alt (work["a"] is ok) { set a = "ok" }
                                    alt (work["b"] is ok) { set b = "ok" } else { set b = "not ok" }
                                }
                                """);

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> results =
                (Map<String, Map<String, Object>>) e.getContext().getVariable("results");
        assertThat(results.keySet()).containsExactly("a", "b");
        assertThat(results.get("a")).containsEntry("status", "completed");
        assertThat(results.get("b")).containsEntry("status", "failed");
        assertThat(e.getContext().getVariable("a")).isEqualTo("ok");
        assertThat(e.getContext().getVariable("b")).isEqualTo("not ok");
    }

    @Test
    void aStartedChildRunsOnItsOwnJournalAndTheParentJournalRecordsOnlyTheStart() {
        run(
                CHILDREN
                        + """
                        workflow Main(q) {
                            start Good(topic = q) budget { tokens: 10 } -> work
                            wait work timeout 1m -> outcome
                        }
                        """);

        assertThat(journals).hasSize(2);
        RunJournal parent = journals.get("parent");
        assertThat(parent.get("Main/s0").orElseThrow().kind()).isEqualTo("child");
        assertThat(parent.get("Main/s1").orElseThrow().kind()).isEqualTo("child-wait");
        String childId = parents.keySet().iterator().next();
        assertThat(journals.get(childId).all().values())
                .anyMatch(entry -> "task".equals(entry.kind()));
        assertThat(parent.all().values()).noneMatch(entry -> "task".equals(entry.kind()));
    }

    @Test
    void aChildsResultComesBackUncheckedBecauseItMayHoldModelText() {
        HarnessExecutor e =
                run(
                        CHILDREN
                                + """
                                workflow Main(q) {
                                    start Good(topic = q) budget { tokens: 10 } -> work
                                    wait work timeout 1m -> outcome
                                }
                                """);

        assertThat(String.valueOf(e.getContext().originOf("outcome"))).containsIgnoringCase("unchecked");
    }

    /** A clock the test moves. */
    private static final class MovingClock extends java.time.Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

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

    @Test
    void aParentWaitingOnASleepingChildSuspendsTheTreeAndAResumeFinishesItWithTheEffectOnce() {
        MovingClock clock = new MovingClock();
        AtomicInteger effect = new AtomicInteger();
        Map<String, RunJournal> stores = new ConcurrentHashMap<>();
        String source =
                """
                workflow Napper(topic) { sleep 1h  run Effect() -> r  return r }
                workflow Main(q) {
                    start Napper(topic = q) budget { tokens: 10 } -> work
                    wait work timeout 5h -> outcome
                }
                """;
        java.util.function.Supplier<HarnessExecutor> parent =
                () -> {
                    TaskRegistry tasks = new TaskRegistry();
                    tasks.register(
                            Task.of(
                                    "Effect",
                                    TaskEffect.CHANGES,
                                    EffectPolicy.DEFAULT,
                                    ctx -> {
                                        effect.incrementAndGet();
                                        return TaskResult.value("slept");
                                    }));
                    HarnessExecutor e =
                            new HarnessExecutor(
                                    StartParseTest.parse(source), new ToolRegistry(), model -> null);
                    e.setTaskRegistry(tasks);
                    e.setClock(clock);
                    e.setRunId("parent");
                    e.setJournal(durable(stores.computeIfAbsent("parent", k -> RunJournal.inMemory())));
                    e.setChildRuns(
                            id -> durable(stores.computeIfAbsent(id, k -> RunJournal.inMemory())));
                    e.setChildScheduler(new ControlledChildScheduler());
                    e.initialize();
                    return e;
                };

        HarnessExecutor first = parent.get();
        assertThatThrownBy(() -> first.executeWorkflow("Main", Map.of("q", "x")))
                .isInstanceOf(io.github.llm4j.loom.runtime.RunSuspended.class)
                .satisfies(
                        e ->
                                assertThat(
                                                ((io.github.llm4j.loom.runtime.RunSuspended) e)
                                                        .resumeAt())
                                        .isEqualTo(Instant.parse("2026-01-01T01:00:00Z")));
        assertThat(effect).hasValue(0);

        clock.advance(Duration.ofHours(2));
        HarnessExecutor second = parent.get();
        second.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(String.valueOf(second.getContext().getVariable("outcome"))).contains("slept");
        assertThat(effect).as("the effect happened once across the restart").hasValue(1);
        assertThat(stores).as("one parent, one child").hasSize(2);
    }

    @Test
    void aHandleHasAStatusAndPrintsAsAFixedWordNeverItsRunId() {
        ChildHandle handle = new ChildHandle("parent.Main.s0", "Good");
        assertThat(handle.status()).isEqualTo(ChildStatus.RUNNING);
        assertThat(handle.toString()).doesNotContain("parent").isEqualTo("[handle to Good]");
        assertThat(handle.isOk()).isFalse();
        handle.pause(ChildStatus.SLEEPING, Instant.parse("2026-01-01T01:00:00Z"));
        assertThat(handle.fact("status")).isEqualTo("sleeping");
        handle.complete("done");
        assertThat(handle.isOk()).isTrue();
        assertThat(List.of(ChildStatus.values()))
                .extracting(ChildStatus::word)
                .contains("timed out", "cancelled", "stopped");
    }
}
