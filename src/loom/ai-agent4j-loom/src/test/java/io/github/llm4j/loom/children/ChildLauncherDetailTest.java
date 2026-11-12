package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.ChildLimits;
import io.github.llm4j.loom.execution.ChildRuns;
import io.github.llm4j.loom.execution.ChildScheduler;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.ChildHandle;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Phase 33: the details of the launcher that decide things: boundaries, what is recorded, and what the host is told. */
class ChildLauncherDetailTest {

    private static final class MovingClock extends java.time.Clock {
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

    private final MovingClock clock = new MovingClock();
    private final Map<String, RunJournal> journals = new ConcurrentHashMap<>();
    private final List<String> settled = Collections.synchronizedList(new ArrayList<>());
    private final List<String> audits = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch inBlocked = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private HarnessExecutor parent;

    private HarnessExecutor executor(String source, ChildScheduler scheduler, ChildLimits limits) {
        TaskRegistry tasks = new TaskRegistry();
        tasks.register(Task.of("Fine", TaskEffect.NONE, EffectPolicy.DEFAULT, ctx -> TaskResult.value("ok")));
        tasks.register(
                Task.of(
                        "Blocked",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            inBlocked.countDown();
                            try {
                                release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return TaskResult.value("unblocked");
                        }));
        tasks.register(
                Task.of(
                        "Pump",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            try {
                                inBlocked.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return TaskResult.value("pumped");
                        }));
        tasks.register(Task.of("Let", TaskEffect.NONE, EffectPolicy.DEFAULT, ctx -> {
            release.countDown();
            return TaskResult.value("let");
        }));
        tasks.register(
                Task.of(
                        "Wait",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                            while (journals.get("p").all().keySet().stream().noneMatch(k -> k.contains("#fire."))
                                    && System.nanoTime() < end) {
                                Thread.onSpinWait();
                            }
                            return TaskResult.value("waited");
                        }));
        tasks.register(
                Task.of(
                        "Later",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            clock.advance(Duration.ofSeconds(Long.parseLong(String.valueOf(ctx.arg("seconds")))));
                            parent.tickChildren();
                            return TaskResult.value("later");
                        }));
        parent = new HarnessExecutor(StartParseTest.parse(source), new ToolRegistry(), new io.github.llm4j.loom.eval.MockModels());
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
                    public void settled(String childRunId, ChildStatus status) {
                        settled.add(status.word());
                    }
                });
        parent.setChildScheduler(scheduler);
        if (limits != null) parent.setChildLimits(limits);
        parent.setAuditLogger(
                new io.github.llm4j.audit.AuditLogger() {
                    @Override
                    public void logAgentDecision(io.github.llm4j.audit.AuditEvent event) {}

                    @Override
                    public void logToolExecution(String s, String t, String i, String o, Instant ts) {}

                    @Override
                    public void logPromptUsage(String s, String p, String v, Instant ts) {}

                    @Override
                    public void logConversationEvent(String s, String u, String type, Map<String, Object> data) {
                        audits.add(type);
                    }
                });
        parent.initialize();
        return parent;
    }

    private HarnessExecutor controlled(String source) {
        return executor(source, new ControlledChildScheduler(), null);
    }

    private static final String LEAF = "workflow Leaf(x) { run Fine() -> r  return r }\n";

    // ---- reservation and limits: boundaries and messages ----

    @Test
    void aStartThatExactlyFitsTheParentsBudgetIsAccepted() {
        HarnessExecutor e =
                controlled(
                        "budget { tokens: 1000 calls: 10 }\n"
                                + LEAF
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) budget { tokens: 500 calls: 5 } -> a
                                    start Leaf(x = q) budget { tokens: 500 calls: 5 } -> b
                                    wait all a timeout 1m
                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("b")).isInstanceOf(ChildHandle.class);
    }

    @Test
    void theCallsOfChildrenAreReservedToo() {
        HarnessExecutor e =
                controlled(
                        "budget { tokens: 100000 calls: 10 }\n"
                                + LEAF
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) budget { calls: 6 } -> a
                                    start Leaf(x = q) budget { calls: 6 } -> b
                                }
                                """);

        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of("q", "x")))
                .hasMessageContaining("6 calls")
                .hasMessageContaining("10 left")
                .hasMessageContaining("could still spend 6");
    }

    @Test
    void theCostOfChildrenIsReservedToo() {
        HarnessExecutor e =
                controlled(
                        "budget { cost: \"$1.00\" }\n"
                                + LEAF
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) budget { cost: "$0.60" } -> a
                                    start Leaf(x = q) budget { cost: "$0.60" } -> b
                                }
                                """);

        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of("q", "x")))
                .hasMessageContaining("$0.60");
    }

    @Test
    void theLimitsAtTheCeilingAreAcceptedAndTheMessagesCountFromOne() {
        HarnessExecutor atCeiling =
                executor(
                        "max_children: 4\nmax_depth: 2\n"
                                + LEAF
                                + "workflow Main(q) { start Leaf(x = q) budget { tokens: 1 } -> a  wait a timeout 1m }",
                        new ControlledChildScheduler(),
                        new ChildLimits(4, 2));
        atCeiling.executeWorkflow("Main", Map.of("q", "x"));

        journals.clear();
        HarnessExecutor tooMany =
                controlled(
                        "max_children: 1\n"
                                + LEAF
                                + "workflow Main(q) { start Leaf(x = q) budget { tokens: 1 } -> a  start Leaf(x = q) budget { tokens: 1 } -> b }");
        assertThatThrownBy(() -> tooMany.executeWorkflow("Main", Map.of("q", "x")))
                .hasMessageContaining("child number 2");

        journals.clear();
        HarnessExecutor deep =
                controlled(
                        "max_depth: 1\n"
                                + LEAF
                                + """
                                workflow Mid(x) {
                                    start Leaf(x = x) budget { tokens: 1 } -> inner
                                    wait inner timeout 1m
                                }
                                workflow Main(q) {
                                    start Mid(x = q) budget { tokens: 1 } -> mid
                                    wait mid timeout 1m
                                    set why = mid.failure
                                }
                                """);
        deep.executeWorkflow("Main", Map.of("q", "x"));
        assertThat(String.valueOf(deep.getContext().getVariable("why"))).contains("2 levels deep");
    }

    @Test
    void aDepthSettingAboveTheCeilingIsRefused() {
        HarnessExecutor e =
                executor(
                        "max_depth: 9\n" + LEAF + "workflow Main(q) { start Leaf(x = q) budget { tokens: 1 } -> a }",
                        new ControlledChildScheduler(),
                        new ChildLimits(4, 2));

        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of("q", "x")))
                .hasMessageContaining("max_depth: 9")
                .hasMessageContaining("ceiling of 2");
    }

    // ---- what is recorded and what the host is told ----

    @Test
    void theHostIsToldHowEachChildEnded() {
        controlled(
                        LEAF
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) timeout 2h budget { tokens: 1 } -> a
                                    wait a timeout 1m
                                    start Leaf(x = q) timeout 90s budget { tokens: 1 } -> b
                                    run Later(seconds = 100) -> later
                                    wait b timeout 1m
                                    start Leaf(x = q) budget { tokens: 1 } -> c
                                    cancel c reason "no"
                                    wait c timeout 1m
                                    set whyB = b.reason
                                }
                                """)
                .executeWorkflow("Main", Map.of("q", "x"));

        assertThat(settled).containsExactly("completed", "timed out", "cancelled");
        assertThat(String.valueOf(parent.getContext().getVariable("whyB"))).contains("90 seconds");
    }

    @Test
    void aTimedOutMessageSaysHoursWhenTheDeadlineIsWholeHours() {
        controlled(
                        LEAF
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) timeout 2h budget { tokens: 1 } -> a
                                    run Later(seconds = 7300) -> later
                                    wait a timeout 1m
                                    set why = a.reason
                                }
                                """)
                .executeWorkflow("Main", Map.of("q", "x"));

        assertThat(String.valueOf(parent.getContext().getVariable("why"))).contains("2 hours");
    }

    @Test
    void theStartIsJournaledWithItsTimeAndAReplayKeepsTheFirstDeadline() {
        // a parent that started the child ten minutes ago, then restarted: the 5 minute deadline is already past
        String childId = ChildRuns.idOf("p", "Main/s0");
        journals.computeIfAbsent("p", k -> RunJournal.inMemory())
                .put(
                        "Main/s0",
                        new RunJournal.Entry(
                                "child",
                                Map.of("run", childId, "workflow", "Leaf", "at", clock.millis() - 600_000L)));

        HarnessExecutor e =
                controlled(
                        LEAF
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) timeout 5m budget { tokens: 1 } -> work
                                    run Later(seconds = 0) -> later
                                    wait work timeout 1m
                                    set seen = work.status
                                }
                                """);
        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("timed out");
    }

    @Test
    void aFreshStartRecordsWhenItHappened() {
        controlled(LEAF + "workflow Main(q) { start Leaf(x = q) budget { tokens: 1 } -> a  wait a timeout 1m }")
                .executeWorkflow("Main", Map.of("q", "x"));

        assertThat(journals.get("p").get("Main/s0").orElseThrow().value())
                .asString()
                .contains("at=" + clock.millis());
    }

    @Test
    void aCancelIsJournaledWithItsReasonAndAReplayDoesNotWriteItAgain() {
        controlled(
                        LEAF
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) budget { tokens: 1 } -> work
                                    cancel work reason "enough"
                                    wait work timeout 1m
                                }
                                """)
                .executeWorkflow("Main", Map.of("q", "x"));

        var entry = journals.get("p").get("Main/s1").orElseThrow();
        assertThat(entry.kind()).isEqualTo("child-cancel");
        assertThat(entry.value()).asString().contains("enough");
    }

    @Test
    void aSupervisorsFiringsAreJournaledInOrderAndAuditedAndTheStepsOfASteerToo() {
        controlled(
                        "agent Looper { model: \"m\" system: \"s\" steerable: true streaming: true }\n"
                                + LEAF
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) budget { tokens: 1 } -> work
                                    supervise work { when elapsed > 1m { steer "a" } }
                                    steer work Looper "b"
                                    run Later(seconds = 120) -> later
                                    wait work timeout 1m
                                }
                                """)
                .executeWorkflow("Main", Map.of("q", "x"));

        assertThat(journals.get("p").all().keySet()).contains("Main/s1#fire.0", "Main/s2");
        assertThat(audits).contains("child_supervise", "child_steer");
        assertThat(journals.get("p").get("Main/s2").orElseThrow().kind()).isEqualTo("child-steer");
    }

    // ---- handles under keys ----

    @Test
    void aKeyedHandleIsReadByLiteralAndByVariableKeyForWaitCancelAndSteer() {
        HarnessExecutor e =
                controlled(
                        LEAF
                                + """
                                workflow Main(q) {
                                    set k = "one"
                                    start Leaf(x = q) budget { tokens: 1 } -> work["fixed"]
                                    start Leaf(x = q) budget { tokens: 1 } -> work[k]
                                    cancel work["fixed"] reason "no"
                                    cancel work[k] reason "no"
                                    wait all work timeout 1m -> results

                                }
                                """);

        e.executeWorkflow("Main", Map.of("q", "x"));

        @SuppressWarnings("unchecked")
        Map<String, ChildHandle> handles = (Map<String, ChildHandle>) e.getContext().getVariable("work");
        assertThat(handles.keySet()).containsExactly("fixed", "one");
        assertThat(handles.values()).allSatisfy(h -> assertThat(h.status()).isEqualTo(ChildStatus.CANCELLED));
    }

    @Test
    void aKeyThatIsNotAPlainWordIsRefusedBeforeAnythingStarts() {
        HarnessExecutor e =
                controlled(
                        LEAF
                                + """
                                workflow Main(q) {
                                    set k = "has space"
                                    start Leaf(x = q) budget { tokens: 1 } -> work[k]
                                }
                                """);

        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of("q", "x")))
                .hasMessageContaining("not a plain word");
        assertThat(journals).as("no child was started").hasSize(1);
    }

    @Test
    void waitingForAKeyThatHoldsNoHandleIsAnError() {
        HarnessExecutor single =
                controlled(
                        LEAF
                                + "workflow Main(q) { start Leaf(x = q) budget { tokens: 1 } -> work[\"a\"]\n wait work[\"zzz\"] timeout 1m }");
        assertThatThrownBy(() -> single.executeWorkflow("Main", Map.of("q", "x")))
                .hasMessageContaining("holds no handle");
    }

    @Test
    void aReplayedWaitReturnsWhatTheFirstRunSawWithoutWaiting() {
        journals.computeIfAbsent("p", k -> RunJournal.inMemory())
                .put(
                        "Main/s1",
                        new RunJournal.Entry(
                                "child-wait", Map.of("work", Map.of("status", "failed", "failure", "the source is down"))));

        HarnessExecutor e =
                controlled(
                        LEAF
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) budget { tokens: 1 } -> work
                                    wait work timeout 1m -> out on_failure { set handled = "yes" }
                                    set seen = work.status
                                    set why = work.failure
                                }
                                """);
        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("failed");
        assertThat(e.getContext().getVariable("why")).isEqualTo("the source is down");
        assertThat(e.getContext().getVariable("handled")).isEqualTo("yes");
    }

    // ---- real threads ----

    @Test
    void aWaitThatTimesOutWhileTheChildIsStillRunningRunsOnTimeoutAndTheChildKeepsGoing() throws Exception {
        HarnessExecutor e =
                executor(
                        LEAF.replace("run Fine() -> r", "run Blocked() -> r")
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) budget { tokens: 1 } -> work
                                    wait work timeout 1s on_timeout { set late = "yes" }
                                    set seen = work.status
                                    run Let() -> let
                                }
                                """,
                        ChildScheduler.threads(),
                        null);

        // the wait counts the injected clock (F3): the test moves it, and nothing sits for a real second
        Thread parentThread = new Thread(() -> e.executeWorkflow("Main", Map.of("q", "x")));
        parentThread.setDaemon(true);
        parentThread.start();
        assertThat(inBlocked.await(10, TimeUnit.SECONDS)).isTrue();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (parentThread.isAlive() && System.nanoTime() < end) {
            clock.advance(Duration.ofSeconds(5));
            parentThread.join(10);
        }

        assertThat(parentThread.isAlive()).isFalse();
        assertThat(e.getContext().getVariable("late")).isEqualTo("yes");
        assertThat(e.getContext().getVariable("seen")).isEqualTo("running");
    }

    @Test
    void theSupervisorsOwnThreadLooksWithoutAnyoneCallingTick() throws Exception {
        HarnessExecutor e =
                executor(
                        LEAF.replace("run Fine() -> r", "run Blocked() -> r")
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) budget { tokens: 1 } -> work
                                    supervise work { when elapsed > 0ms { wrap_up } }
                                    run Pump() -> pumped
                                    run Wait() -> waited
                                    run Let() -> let
                                    wait work timeout 10s
                                }
                                """,
                        ChildScheduler.threads(),
                        null);
        e.setSuperviseTick(Duration.ofMillis(30));
        e.setClock(java.time.Clock.systemUTC()); // the supervisor's own thread keeps real time
        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(journals.get("p").all().keySet()).anyMatch(k -> k.contains("#fire."));
    }
}
