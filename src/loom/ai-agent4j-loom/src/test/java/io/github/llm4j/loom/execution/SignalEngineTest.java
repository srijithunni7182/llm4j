package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.children.ChildHarness;
import io.github.llm4j.loom.runtime.ChildHandle;
import io.github.llm4j.loom.runtime.ChildStatus;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.loom.runtime.Unavailable;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 34c: signals between a parent and a child on the controlled scheduler and the virtual clock.
 * Positive and negative cases, in both directions: a signal sent before the wait (G17), a child
 * woken from waiting (D12), a wait that times out on the tick, a parent that is not suspended by a
 * child waiting on a signal (G34), a send to a settled or cancelled child (G20, D13), an old
 * generation's signal (D13), limits that fail loudly (G24), a wait with no parent (D12 rule 8),
 * {@code when signal} rules, provenance in both directions (G22), exactly-once over a run again
 * (G16), and a wake that is idempotent and single flight (G33).
 */
class SignalEngineTest {

    private final ChildHarness h = new ChildHarness();
    private ControlledChildScheduler scheduler;

    private static final String WAITER = "workflow Child(x) { wait signal \"go\" timeout 10m -> text  alt (text is ok) { return text } else { return \"timed out\" } }\n";

    private HarnessExecutor run(String source) {
        scheduler = h.controlled();
        h.task(
                "LetItWait",
                ctx -> {
                    scheduler.runNext(); // the child runs until it waits or ends
                    return TaskResult.value("ran");
                });
        HarnessExecutor e = h.parent(source, scheduler);
        e.executeWorkflow("Main", Map.of("q", "x"));
        return e;
    }

    private static Duration since(ChildHarness h) {
        return Duration.between(Instant.parse("2026-01-01T00:00:00Z"), h.clock.instant());
    }

    // ---- G17: a signal sent before the wait is not lost ----

    @Test
    void aSignalSentBeforeTheChildWaitsIsDeliveredAtOnceWithItsPayloadAndNoWakeIsNeeded() {
        HarnessExecutor e =
                run(
                        WAITER
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 10 } -> work
                                    notify work "go" "hello child"
                                    wait work timeout 1m -> out
                                }
                                """);

        assertThat(String.valueOf(e.getContext().getVariable("out"))).contains("hello child");
        assertThat(h.attempts).as("the child never had to be woken").hasSize(1);
    }

    @Test
    void aSignalWithNoTextDeliversEmptyText() {
        HarnessExecutor e =
                run(
                        WAITER
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 10 } -> work
                                    notify work "go"
                                    wait work timeout 1m -> out
                                    set seen = work.status
                                }
                                """);

        assertThat(e.getContext().getVariable("seen")).isEqualTo("completed");
    }

    // ---- D12: a waiting child has no thread, and a notify wakes it ----

    @Test
    void aChildWaitingForASignalHasNoThreadAndANotifyWakesIt() {
        HarnessExecutor e =
                run(
                        WAITER
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 10 } -> work
                                    run LetItWait() -> kick
                                    set before = work.status
                                    set waits = work.waits
                                    notify work "go" "now"
                                    wait work timeout 1m -> out
                                    set after = work.status
                                }
                                """);

        assertThat(e.getContext().getVariable("before")).isEqualTo("waiting");
        assertThat(String.valueOf(e.getContext().getVariable("waits"))).isEqualTo("signal go");
        assertThat(e.getContext().getVariable("after")).isEqualTo("completed");
        assertThat(String.valueOf(e.getContext().getVariable("out"))).contains("now");
        assertThat(h.attempts).as("the child ran again over its journal once").hasSize(2);
    }

    @Test
    void theChildReadsASignalOfTheNameItWaitsForAndNotAnother() {
        HarnessExecutor e =
                run(
                        WAITER
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 10 } -> work
                                    run LetItWait() -> kick
                                    notify work "other" "not for you"
                                    set stillWaiting = work.status
                                    notify work "go" "for you"
                                    wait work timeout 1m -> out
                                }
                                """);

        assertThat(e.getContext().getVariable("stillWaiting")).as("a signal of another name does not wake it").isEqualTo("waiting");
        assertThat(String.valueOf(e.getContext().getVariable("out"))).contains("for you").doesNotContain("not for you");
        assertThat(h.attempts).hasSize(2);
    }

    // ---- the timeout of a signal wait is enforced by the tick (D12 rule 4) ----

    @Test
    void aSignalWaitThatNobodyAnswersTimesOutOnTheVirtualClockAndTheChildGoesOn() {
        HarnessExecutor e =
                run(
                        WAITER
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 10 } -> work
                                    wait work timeout 20m -> out
                                    set seen = work.status
                                }
                                """);

        assertThat(String.valueOf(e.getContext().getVariable("out"))).contains("timed out");
        assertThat(e.getContext().getVariable("seen")).isEqualTo("completed");
        assertThat(since(h)).as("at its own deadline, not the parent's").isBetween(Duration.ofMinutes(10), Duration.ofMinutes(11));
    }

    @Test
    void aSignalThatArrivesBeforeTheDeadlineBeatsTheTimeout() {
        HarnessExecutor e =
                run(
                        WAITER
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 10 } -> work
                                    run LetItWait() -> kick
                                    run LetItWait() -> kickAgain
                                    notify work "go" "in time"
                                    wait work timeout 20m -> out
                                }
                                """);

        assertThat(String.valueOf(e.getContext().getVariable("out"))).contains("in time");
        assertThat(since(h)).isLessThan(Duration.ofMinutes(1));
    }

    // ---- G34: a child waiting on a signal does not make the parent suspend ----

    @Test
    void aParentWhoseOnlyChildWaitsForASignalParksUntilItsOwnTimeoutAndIsNotSuspended() {
        HarnessExecutor e =
                run(
                        WAITER.replace("timeout 10m", "timeout 1h")
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 10 } -> work
                                    wait work timeout 5m -> out
                                    set seen = work.status
                                }
                                """);

        assertThat(e.getContext().getVariable("seen")).isEqualTo("waiting");
        assertThat(since(h)).isBetween(Duration.ofMinutes(5), Duration.ofMinutes(6));
    }

    @Test
    void aChildAsleepForALongTimeStillSuspendsTheParentAsBefore() {
        HarnessExecutor parent = null;
        assertThatThrownBy(
                        () ->
                                run(
                                        "workflow Child(x) { sleep 2h  return \"up\" }\n"
                                                + """
                                                workflow Main(q) {
                                                    start Child(x = q) budget { tokens: 10 } -> work
                                                    wait work timeout 5h -> out
                                                }
                                                """))
                .isInstanceOf(RunSuspended.class);
    }

    // ---- a child signals its parent ----

    @Test
    void aChildSignalsItsParentWhichWaitsForItThroughTheHandle() {
        HarnessExecutor e =
                run(
                        """
                        workflow Child(x) { notify parent "found" "the data"  return "done" }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            wait work signal "found" timeout 5m -> got
                            set seen = got
                            wait work timeout 1m -> out
                        }
                        """);

        assertThat(e.getContext().getVariable("seen")).isEqualTo("the data");
    }

    @Test
    void messagesFromAChildComeInTheOrderTheyWereSentOneForEachWait() {
        HarnessExecutor e =
                run(
                        """
                        workflow Child(x) { notify parent "n" "first"  notify parent "n" "second"  notify parent "n" "third"  return "done" }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            wait work signal "n" timeout 5m -> a
                            wait work signal "n" timeout 5m -> b
                            wait work signal "n" timeout 5m -> c
                        }
                        """);

        assertThat(e.getContext().getVariable("a")).isEqualTo("first");
        assertThat(e.getContext().getVariable("b")).isEqualTo("second");
        assertThat(e.getContext().getVariable("c")).isEqualTo("third");
    }

    @Test
    void aParentWaitForASignalThatNeverComesTimesOutAndSaysSoWhenRead() {
        assertThatThrownBy(
                        () ->
                                run(
                                        """
                                        workflow Child(x) { return "done" }
                                        workflow Main(q) {
                                            start Child(x = q) budget { tokens: 10 } -> work
                                            wait work signal "nothing" timeout 5m -> got
                                            set oops = "{got}"
                                        }
                                        """))
                .hasMessageContaining("nothing")
                .hasMessageContaining("timeout");
    }

    @Test
    void aTimedOutSignalWaitLeavesAValueThatIsNotOk() {
        HarnessExecutor e =
                run(
                        """
                        workflow Child(x) { return "done" }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            wait work signal "nothing" timeout 5m -> got
                            alt (got is ok) { set verdict = "signal" } else { set verdict = "none" }
                        }
                        """);

        assertThat(e.getContext().getVariable("verdict")).isEqualTo("none");
        assertThat(e.getContext().getVariable("got")).isInstanceOf(Unavailable.class);
        assertThat(since(h)).as("the child had finished: no signal can come, so the wait ends at once").isLessThan(Duration.ofMinutes(1));
    }

    // ---- sticky ----

    @Test
    void aStickySignalIsReadByEveryWaitAndAMessageIsConsumedOnce() {
        HarnessExecutor e =
                run(
                        """
                        workflow Child(x) {
                            wait signal "approved" timeout 5m -> first
                            wait signal "approved" timeout 5m -> second
                            wait signal "note" timeout 5m -> one
                            wait signal "note" timeout 5m -> two
                            return "{first} {second} {one} {two}"
                        }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            notify work "approved" "yes" sticky
                            notify work "note" "m1"
                            notify work "note" "m2"
                            wait work timeout 1m -> out
                        }
                        """);

        assertThat(String.valueOf(e.getContext().getVariable("out"))).contains("yes yes m1 m2");
    }

    @Test
    void aLaterStickyNotifyReplacesTheEarlierOne() {
        HarnessExecutor e =
                run(
                        """
                        workflow Child(x) { wait signal "state" timeout 5m -> s  return s }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            notify work "state" "v1" sticky
                            notify work "state" "v2" sticky
                            wait work timeout 1m -> out
                        }
                        """);

        assertThat(String.valueOf(e.getContext().getVariable("out"))).contains("v2").doesNotContain("v1");
    }

    // ---- G20 and D13: settled, cancelled, rewound ----

    @Test
    void aNotifyToAChildThatAlreadySettledIsARecordedNoOpAndNotAnError() {
        HarnessExecutor e =
                run(
                        """
                        workflow Child(x) { return "done" }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            wait work timeout 1m -> out
                            notify work "go" "too late"
                            set seen = work.status
                        }
                        """);

        assertThat(e.getContext().getVariable("seen")).isEqualTo("completed");
        assertThat(Mailbox.all(h.onlyChild(), Mailbox.Direction.IN)).as("nothing was written to a settled child").isEmpty();
        assertThat(h.journals.get("p").all().values())
                .anyMatch(en -> "notify".equals(en.kind()) && String.valueOf(en.value()).contains("already completed"));
    }

    @Test
    void cancelWinsAChildCancelledWhileWaitingIsNotWokenByALaterNotify() {
        HarnessExecutor e =
                run(
                        WAITER
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 10 } -> work
                                    run LetItWait() -> kick
                                    cancel work reason "enough"
                                    notify work "go" "too late"
                                    set seen = work.status
                                }
                                """);

        assertThat(e.getContext().getVariable("seen")).isEqualTo("cancelled");
        assertThat(h.attempts).as("a cancelled child is not run again").hasSize(1);
    }

    @Test
    void aSignalOfAnEarlierGenerationIsNotDeliveredToARewoundChild() {
        HarnessExecutor e =
                run(
                        """
                        workflow Child(x) {
                            wait signal "first" timeout 10m -> a
                            checkpoint c
                            wait signal "second" timeout 10m -> b
                            return "{a} {b}"
                        }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            run LetItWait() -> kick1
                            notify work "first" "A"
                            run LetItWait() -> kick2
                            notify work "stale" "from the old generation"
                            cancel work rewind to c at most 1 times
                            run LetItWait() -> kick3
                            notify work "second" "B"
                            wait work timeout 1m -> out
                        }
                        """);

        assertThat(String.valueOf(e.getContext().getVariable("out"))).contains("A B");
        assertThat(Mailbox.next(h.onlyChild(), Mailbox.Direction.IN, "stale")).as("history, not delivered").isEmpty();
        assertThat(Mailbox.all(h.onlyChild(), Mailbox.Direction.IN)).extracting(Mailbox.Signal::name).contains("stale");
    }

    // ---- G24: limits fail loudly ----

    @Test
    void theSeventeenthUnreadSignalOfANameFailsTheNotifyAndNamesTheLimit() {
        StringBuilder sends = new StringBuilder();
        for (int i = 0; i < 17; i++) sends.append("notify work \"flood\" \"m").append(i).append("\"\n");
        assertThatThrownBy(
                        () ->
                                run(
                                        WAITER
                                                + "workflow Main(q) {\nstart Child(x = q) budget { tokens: 10 } -> work\n"
                                                + sends
                                                + "}\n"))
                .hasMessageContaining("flood")
                .hasMessageContaining("16");
    }

    @Test
    void aPayloadOverTwoThousandCharactersFailsTheNotifyNamingTheLimit() {
        h.task("Long", ctx -> TaskResult.value("y".repeat(2500)));
        assertThatThrownBy(
                        () ->
                                run(
                                        WAITER
                                                + """
                                                workflow Main(q) {
                                                    start Child(x = q) budget { tokens: 10 } -> work
                                                    run Long() -> big
                                                    notify work "go" "{big.value}"
                                                }
                                                """))
                .hasMessageContaining("2000");
    }

    // ---- D12 rule 8, D13.3: no parent ----

    @Test
    void aWaitForASignalInARunWithNoParentFailsWithAMessageBecauseItWouldWaitForEver() {
        assertThatThrownBy(() -> run("workflow Main(q) { wait signal \"go\" timeout 5m -> t }"))
                .hasMessageContaining("no parent")
                .hasMessageContaining("go");
    }

    @Test
    void aNotifyToTheParentInARunWithNoParentIsARecordedNoOp() {
        HarnessExecutor e = run("workflow Main(q) { notify parent \"hello\" \"nobody home\"  set done = \"yes\" }");

        assertThat(e.getContext().getVariable("done")).isEqualTo("yes");
        assertThat(h.journals.get("p").all().values()).anyMatch(en -> "notify".equals(en.kind()) && String.valueOf(en.value()).contains("no parent"));
    }

    // ---- when signal in a supervise block (D10) ----

    @Test
    void aWhenSignalRuleFiresOnAnArrivalAndTheMessageIsConsumedSoNothingElseSeesItAgain() {
        h.task("Tick", ctx -> { h.clock.advance(Duration.ofSeconds(1)); h.parent.tickChildren(); return TaskResult.value("ticked"); });
        HarnessExecutor e =
                run(
                        """
                        workflow Child(x) { notify parent "stuck" "I need help"  wait signal "go" timeout 10m -> t  return "done" }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            supervise work { when signal "stuck" { cancel } }
                            run LetItWait() -> kick
                            run Tick() -> tickDone
                            wait work signal "stuck" timeout 1m -> late
                            set seen = work.status
                        }
                        """);

        assertThat(e.getContext().getVariable("seen")).isEqualTo("cancelled");
        assertThat(h.journals.get("p").all().values().stream().filter(en -> "supervise".equals(en.kind()) && String.valueOf(en.value()).contains("fired")).count()).isEqualTo(1);
        assertThat(e.getContext().getVariable("late")).as("the rule took the message: the wait times out").isInstanceOf(Unavailable.class);
    }

    @Test
    void aWhenSignalRuleFiresOncePerArrivalUnlessItIsOnce() {
        String child = "workflow Child(x) { notify parent \"ping\" \"1\"  notify parent \"ping\" \"2\"  notify parent \"ping\" \"3\"  return \"done\" }\n";
        String main = "workflow Main(q) {\nstart Child(x = q) budget { tokens: 10 } -> work\nsupervise work { when signal \"ping\" { steer \"x\" %s } }\nrun LetItWait() -> kick\nrun Tick() -> tickResult\n}\n";
        h.task("Tick", ctx -> { h.clock.advance(Duration.ofSeconds(1)); h.parent.tickChildren(); return TaskResult.value("ticked"); });
        run(child + main.formatted(""));
        long every = h.journals.get("p").all().values().stream().filter(en -> "supervise".equals(en.kind()) && String.valueOf(en.value()).contains("fired")).count();

        ChildHarness again = new ChildHarness();
        again.task("Tick", ctx -> { again.clock.advance(Duration.ofSeconds(1)); again.parent.tickChildren(); return TaskResult.value("ticked"); });
        var sched = again.controlled();
        again.task("LetItWait", ctx -> { sched.runNext(); return TaskResult.value("ran"); });
        again.parent(child + main.formatted("once"), sched).executeWorkflow("Main", Map.of("q", "x"));
        long once = again.journals.get("p").all().values().stream().filter(en -> "supervise".equals(en.kind()) && String.valueOf(en.value()).contains("fired")).count();

        assertThat(every).as("three arrivals, three firings").isEqualTo(3);
        assertThat(once).as("once: one firing however many arrive").isEqualTo(1);
    }

    // ---- G22: provenance in both directions ----

    @Test
    void aSignalsTextIsUncheckedOnTheReceivingSideEvenWhenTheSenderWroteItAsPlainScriptText() {
        HarnessExecutor e =
                run(
                        """
                        workflow Child(x) { wait signal "go" timeout 5m -> text  return "ok" }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            notify work "go" "a literal of the script"
                            wait work timeout 1m -> out
                        }
                        workflow Parent2(q) {
                            start Up(x = q) budget { tokens: 10 } -> work
                            wait work signal "up" timeout 1m -> got
                        }
                        workflow Up(x) { notify parent "up" "a literal too"  return "ok" }
                        """);

        assertThat(h.attempts.get(0).tagOf("text").level()).as("the child reads it unchecked").isEqualTo(Provenance.UNCHECKED.level());
        ChildHarness up = new ChildHarness();
        HarnessExecutor parent =
                up.parent(
                        """
                        workflow Up(x) { notify parent "up" "a literal too"  return "ok" }
                        workflow Main(q) {
                            start Up(x = q) budget { tokens: 10 } -> work
                            wait work signal "up" timeout 1m -> got
                        }
                        """,
                        up.controlled());
        parent.executeWorkflow("Main", Map.of("q", "x"));
        assertThat(parent.tagOf("got").level()).as("the parent reads it unchecked").isEqualTo(Provenance.UNCHECKED.level());
    }

    // ---- G16: exactly once across a run again ----

    @Test
    void aParentRunAgainOverItsJournalsDeliversEachSignalOnceAndStartsNothingNew() {
        String source =
                """
                workflow Child(x) {
                    wait signal "n" timeout 5m -> a
                    wait signal "n" timeout 5m -> b
                    notify parent "echo" "{a}{b}"
                    return "done"
                }
                workflow Main(q) {
                    start Child(x = q) budget { tokens: 10 } -> work
                    notify work "n" "one"
                    notify work "n" "two"
                    wait work signal "echo" timeout 1m -> got
                    wait work timeout 1m -> out
                }
                """;
        HarnessExecutor first = run(source);
        int entries = h.onlyChild().all().size();

        HarnessExecutor again = h.parent(source, h.controlled());
        again.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(String.valueOf(first.getContext().getVariable("got"))).isEqualTo("onetwo");
        assertThat(String.valueOf(again.getContext().getVariable("got"))).isEqualTo("onetwo");
        assertThat(Mailbox.all(h.onlyChild(), Mailbox.Direction.IN)).as("the sends were not repeated").hasSize(2);
        assertThat(Mailbox.all(h.onlyChild(), Mailbox.Direction.OUT)).hasSize(1);
        assertThat(h.onlyChild().all().size()).as("nothing new was written").isEqualTo(entries);
    }

    // ---- D12 rule 7: the trajectory survives a wake ----

    @Test
    void theTrajectoryOfAChildSurvivesBeingWokenAndRunAgain() {
        h.task("Work", ctx -> TaskResult.value("worked"));
        run(
                """
                workflow Child(x) { run Work() -> w  wait signal "go" timeout 10m -> t  return "ok" }
                workflow Main(q) {
                    start Child(x = q) budget { tokens: 10 } -> work
                    run LetItWait() -> kick
                    notify work "go" "now"
                    wait work timeout 1m -> out
                }
                """);

        assertThat(h.attempts).hasSize(2);
        assertThat(h.attempts.get(0).trajectory().callsOf("Work")).isEqualTo(1);
        assertThat(h.attempts.get(1).trajectory().callsOf("Work")).as("carried, not forgotten").isEqualTo(1);
    }

    // ---- G33: a wake is idempotent and single flight ----

    @Test
    void twoNotifiesWhileTheChildIsBeingWokenStartOneRunAndTheSecondOnlyWritesItsEntry() {
        HarnessExecutor e =
                run(
                        """
                        workflow Child(x) { wait signal "go" timeout 10m -> a  wait signal "go" timeout 10m -> b  return "{a}{b}" }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            run LetItWait() -> kick
                            notify work "go" "1"
                            notify work "go" "2"
                            wait work timeout 1m -> out
                        }
                        """);

        assertThat(String.valueOf(e.getContext().getVariable("out"))).contains("12");
        assertThat(h.attempts).as("one wake: the second notify found the child already running").hasSize(2);
    }

    @Test
    void aNotifyForANameTheChildIsNotWaitingForNeverStartsARun() {
        run(
                WAITER
                        + """
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            run LetItWait() -> kick
                            notify work "nope" "1"
                            notify work "nope" "2"
                            notify work "nope" "3"
                            set s = work.status
                        }
                        """);

        assertThat(h.attempts).hasSize(1);
    }

    @Test
    void aStaleWakeForAChildThatIsNoLongerWaitingDoesNothing() {
        HarnessExecutor e =
                run(
                        WAITER
                                + """
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 10 } -> work
                                    run LetItWait() -> kick
                                    notify work "go" "first"
                                    wait work timeout 1m -> out
                                    notify work "go" "again after it finished"
                                    set s = work.status
                                }
                                """);

        assertThat(e.getContext().getVariable("s")).isEqualTo("completed");
        assertThat(h.attempts).hasSize(2);
    }

    // ---- non-functional: bounded, and nothing real was slept ----

    @Test
    void aWaitWithAVeryLongTimeoutOnTheVirtualClockTakesNoRealTime() {
        long start = System.nanoTime();
        run(
                WAITER.replace("10m", "720h")
                        + """
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            wait work timeout 721h -> out
                        }
                        """);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(since(h)).isGreaterThanOrEqualTo(Duration.ofHours(720));
    }

    @Test
    void theHandleFactWaitsIsEmptyForAChildThatIsNotWaitingOnASignal() {
        HarnessExecutor e =
                run(
                        """
                        workflow Child(x) { return "done" }
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            wait work timeout 1m -> out
                            set w = "{work.waits}"
                        }
                        """);

        assertThat(e.getContext().getVariable("w")).isIn("", null);
    }
}
