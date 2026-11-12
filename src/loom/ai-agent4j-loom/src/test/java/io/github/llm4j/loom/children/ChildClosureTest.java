package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.runtime.Unavailable;
import org.junit.jupiter.api.Test;

/**
 * Phase 34a, closing the gaps of phase 33 (F1 to F9): the engine tests on the controlled
 * scheduler. Each test is a guard written before the code it guards.
 */
class ChildClosureTest {

    private final ChildHarness h = new ChildHarness();

    private static final String CHILDREN =
            """
            workflow Good(topic) { run Fine() -> r  return r }
            workflow Bad(topic) { run Explode() -> r  return r }
            """;

    private HarnessExecutor run(String main) {
        HarnessExecutor e = h.parent(CHILDREN + main, new ControlledChildScheduler());
        e.executeWorkflow("Main", java.util.Map.of("q", "x"));
        return e;
    }

    // ---- F2, guard G27: an unfinished child's -> r fails closed ----

    @Test
    void aFailedChildLeavesAnUnavailableValueThatNamesItsStatus() {
        HarnessExecutor e =
                run(
                        """
                        workflow Main(q) {
                            start Bad(topic = q) budget { tokens: 10 } -> work
                            wait work timeout 1m -> outcome on_failure { set handled = "yes" }
                        }
                        """);

        assertThat(e.getContext().getVariable("outcome")).isInstanceOf(Unavailable.class);
        assertThat(e.getContext().getVariable("handled")).isEqualTo("yes");
    }

    @Test
    void readingTheResultOfAFailedChildInTextRaisesTheStatusError() {
        assertThatThrownBy(
                        () ->
                                run(
                                        """
                                        workflow Main(q) {
                                            start Bad(topic = q) budget { tokens: 10 } -> work
                                            wait work timeout 1m -> outcome
                                            set seen = "Result: {outcome}"
                                        }
                                        """))
                .hasMessageContaining("failed")
                .hasMessageContaining("Bad");
    }

    @Test
    void aWaitAllOutcomeOfAFailedChildIsStillReadableAsAStatus() {
        HarnessExecutor e =
                run(
                        """
                        workflow Main(q) {
                            start Bad(topic = q) budget { tokens: 10 } -> work["a"]
                            wait all work timeout 1m -> outcomes
                            set seen = outcomes.a.status
                        }
                        """);

        assertThat(e.getContext().getVariable("seen")).isEqualTo("failed");
    }

    @Test
    void aCompletedChildsResultIsAnOrdinaryValue() {
        HarnessExecutor e =
                run(
                        """
                        workflow Main(q) {
                            start Good(topic = q) budget { tokens: 10 } -> work
                            wait work timeout 1m -> outcome
                            set seen = "Result: {outcome}"
                        }
                        """);

        assertThat(e.getContext().getVariable("seen")).isEqualTo("Result: {outcome=ok, value=ok}");
    }

    @Test
    void weaveCheckWarnsWhenAnOutcomeIsReadWithoutCheckingTheChildFinished() {
        var script =
                StartParseTest.parse(
                        CHILDREN
                                + """
                                workflow Main(q) {
                                    start Bad(topic = q) budget { tokens: 10 } -> work
                                    wait work timeout 1m -> outcome
                                    set seen = "Result: {outcome}"
                                }
                                """);
        var warnings =
                new io.github.llm4j.loom.execution.ScriptValidator()
                        .validate(
                                script,
                                new io.github.llm4j.loom.execution.ScriptValidator.Context()
                                        .registeredTools(java.util.Set.of())
                                        .baseDir(java.nio.file.Path.of(".")));

        assertThat(warnings)
                .anyMatch(
                        p ->
                                p.severity() == io.github.llm4j.loom.execution.ScriptValidator.Severity.WARNING
                                        && p.message().contains("outcome")
                                        && p.message().contains("is ok"));
    }

    // ---- F3, guard G28: a wait times out on the injected clock, not on wall time ----

    /**
     * A child holds in an effect while a thread moves the virtual clock past the parent's
     * timeout. A wait that counted wall time would sit for the full minute; one on the injected
     * clock returns as soon as the virtual minute is up, with the child still running.
     */
    @Test
    void aWaitThatTimesOutOnVirtualTimeReturnsWithTheChildStillRunning() throws Exception {
        java.util.concurrent.CountDownLatch inEffect = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        h.task(
                "Hang",
                ctx -> {
                    inEffect.countDown();
                    try {
                        release.await(20, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return io.github.llm4j.agent.task.TaskResult.value("late");
                });
        h.task(
                "Free",
                ctx -> {
                    release.countDown();
                    return io.github.llm4j.agent.task.TaskResult.value("freed");
                });
        HarnessExecutor e =
                h.parent(
                        """
                        workflow Slow(x) { run Hang() -> r  return r }
                        workflow Main(q) {
                            start Slow(x = q) budget { tokens: 1 } -> work
                            wait work timeout 1m -> out on_timeout { set timedOut = "yes" }
                            set seen = work.status
                            run Free() -> freed
                        }
                        """,
                        io.github.llm4j.loom.execution.ChildScheduler.threads());
        Thread parent = new Thread(() -> e.executeWorkflow("Main", java.util.Map.of("q", "x")));
        parent.setDaemon(true);
        parent.start();
        assertThat(inEffect.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (parent.isAlive() && System.nanoTime() < end) {
            h.clock.advance(java.time.Duration.ofMinutes(2)); // virtual time only
            parent.join(10);
        }

        assertThat(parent.isAlive()).as("the wait returned without a real minute passing").isFalse();
        assertThat(e.getContext().getVariable("seen")).isEqualTo("running");
        assertThat(e.getContext().getVariable("timedOut")).isEqualTo("yes");
    }

    // ---- F12, guard G26: a start reserves its paired judge's budget ----

    private static final String JUDGED_SCRIPT =
            """
            budget { tokens: 100 }
            evaluator J { model: "m" }
            workflow Child(x) { run Fine() -> r  return r }
            """;

    private static final String JUDGE = "assert \"on topic\" using J every 2m max 2 { wrap_up }";

    @Test
    void aStartThatCannotCoverTheChildAndItsPairedJudgeIsRefusedAndSaysSo() {
        assertThatThrownBy(
                        () ->
                                h.parent(
                                                JUDGED_SCRIPT
                                                        + """
                                                        workflow Main(q) {
                                                            start Child(x = q) budget { tokens: 60 } -> work
                                                            supervise work budget { tokens: 50 } { %s }
                                                            wait work timeout 1m
                                                        }
                                                        """.formatted(JUDGE),
                                                new ControlledChildScheduler())
                                        .executeWorkflow("Main", java.util.Map.of("q", "x")))
                .hasMessageContaining("start Child cannot reserve")
                .hasMessageContaining("judge")
                .hasMessageContaining("110");
    }

    @Test
    void theSameStartWithNoJudgeIsAccepted() {
        HarnessExecutor e =
                h.parent(
                        JUDGED_SCRIPT
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 60 } -> work
                                    wait work timeout 1m
                                }
                                """,
                        new ControlledChildScheduler());

        e.executeWorkflow("Main", java.util.Map.of("q", "x"));

        assertThat(e.getContext().getVariable("work")).isNotNull();
    }

    @Test
    void aLiveChildHoldsBackItsJudgeToo() {
        assertThatThrownBy(
                        () ->
                                h.parent(
                                                JUDGED_SCRIPT
                                                        + """
                                                        workflow Main(q) {
                                                            start Child(x = q) budget { tokens: 40 } -> a
                                                            supervise a budget { tokens: 30 } { %s }
                                                            start Child(x = q) budget { tokens: 40 } -> b
                                                            supervise b budget { tokens: 30 } { %s }
                                                            wait all a timeout 1m
                                                        }
                                                        """.formatted(JUDGE, JUDGE),
                                                new ControlledChildScheduler())
                                        .executeWorkflow("Main", java.util.Map.of("q", "x")))
                .hasMessageContaining("start Child cannot reserve")
                .hasMessageContaining("could still spend 70");
    }

    @Test
    void aSuperviseThatCouldNotBePairedReservesWhenItRunsAndFailsSayingTheChildIsUnsupervised() {
        assertThatThrownBy(
                        () ->
                                h.parent(
                                                JUDGED_SCRIPT
                                                        + """
                                                        workflow Main(q) {
                                                            start Child(x = q) budget { tokens: 60 } -> work
                                                            alt (q == "x") {
                                                                supervise work budget { tokens: 50 } { %s }
                                                            }
                                                            wait work timeout 1m
                                                        }
                                                        """.formatted(JUDGE),
                                                new ControlledChildScheduler())
                                        .executeWorkflow("Main", java.util.Map.of("q", "x")))
                .hasMessageContaining("already running")
                .hasMessageContaining("unsupervised")
                .hasMessageContaining("50");
    }
}
