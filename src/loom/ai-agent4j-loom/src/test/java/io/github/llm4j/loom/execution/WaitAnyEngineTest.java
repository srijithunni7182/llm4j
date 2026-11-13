package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.children.ChildHarness;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 34d, D11 and guard G18: {@code wait any} returns the first of a signal from any handle, a
 * handle settling, or the timeout, as an event. Signals come before finished children, then the
 * order the handles were started; a finished child is reported once and a message is consumed once;
 * the first return is journaled so a replay returns the same event and never decides again.
 */
class WaitAnyEngineTest {

    private final ChildHarness h = new ChildHarness();

    private static final String CHILDREN =
            """
            workflow Quick(x) { return "result {x}" }
            workflow Chatty(x) { notify parent "hello" "hi from {x}"  return "done {x}" }
            workflow Failing(x) { halt "boom" }
            workflow Waiter(x) { wait signal "go" timeout 1h -> t  return "woke" }
            workflow Napper(x) { sleep 2h  return "up" }
            workflow Latch(x) { notify parent "latch" "v1" sticky  wait signal "never" timeout 1h -> t  return "x" }
            """;

    private static final String START3 =
            "start Quick(x = \"a\") budget { tokens: 1 } -> work[\"a\"]\nstart Quick(x = \"b\") budget { tokens: 1 } -> work[\"b\"]\nstart Quick(x = \"c\") budget { tokens: 1 } -> work[\"c\"]\n";

    private HarnessExecutor run(String main) {
        HarnessExecutor e = h.parent(CHILDREN + "workflow Main(q) {\n" + main + "\n}\n", h.controlled());
        e.executeWorkflow("Main", Map.of("q", "x"));
        return e;
    }

    private static Duration since(ChildHarness h) {
        return Duration.between(Instant.parse("2026-01-01T00:00:00Z"), h.clock.instant());
    }

    // ---- finished children, one event each, in the order started ----

    @Test
    void eachFinishedChildIsReportedOnceInStartOrderAndRemainingCountsDown() {
        HarnessExecutor e =
                run(
                        START3
                                + """
                                wait any work timeout 5m -> e1
                                wait any work timeout 5m -> e2
                                wait any work timeout 5m -> e3
                                set names = "{e1.name}{e2.name}{e3.name}"
                                set left = "{e1.remaining}{e2.remaining}{e3.remaining}"
                                set kinds = "{e1.kind}-{e2.kind}-{e3.kind}"
                                set first = e1.result
                                """);

        assertThat(e.getContext().getVariable("names")).isEqualTo("abc");
        assertThat(e.getContext().getVariable("left")).isEqualTo("210");
        assertThat(e.getContext().getVariable("kinds")).isEqualTo("finished-finished-finished");
        assertThat(e.getContext().getVariable("first")).isEqualTo("result a");
    }

    @Test
    void aCollectingLoopEndsWhenNothingRemainsAndSeesEveryChild() {
        HarnessExecutor e =
                run(
                        START3
                                + """
                                set seen = ""
                                set e = "start"
                                loop until (e.remaining == 0) max 6 {
                                    wait any work timeout 5m -> e
                                    set seen = "{seen}{e.name}"
                                }
                                """);

        assertThat(e.getContext().getVariable("seen")).isEqualTo("abc");
    }

    @Test
    void aFailedChildIsAFinishedEventWhoseResultRaisesWhenRead() {
        HarnessExecutor e =
                run(
                        """
                        start Failing(x = "a") budget { tokens: 1 } -> work["a"]
                        wait any work timeout 5m -> e1
                        set status = e1.status
                        """);
        assertThat(e.getContext().getVariable("status")).isEqualTo("failed");

        assertThatThrownBy(
                        () ->
                                run(
                                        """
                                        start Failing(x = "a") budget { tokens: 1 } -> work["a"]
                                        wait any work timeout 5m -> e1
                                        set oops = "{e1.result}"
                                        """))
                .hasMessageContaining("failed");
    }

    // ---- signals before finished (D11) ----

    @Test
    void aSignalComesBeforeTheFinishOfTheChildThatSentItAndTheChildIsReportedAfter() {
        HarnessExecutor e =
                run(
                        """
                        start Chatty(x = "a") budget { tokens: 1 } -> work["a"]
                        start Quick(x = "b") budget { tokens: 1 } -> work["b"]
                        wait any work timeout 5m -> e1
                        wait any work timeout 5m -> e2
                        wait any work timeout 5m -> e3
                        set order = "{e1.kind}:{e1.name} {e2.kind}:{e2.name} {e3.kind}:{e3.name}"
                        set text = e1.text
                        set signal = e1.signal
                        """);

        assertThat(e.getContext().getVariable("order")).isEqualTo("signal:a finished:a finished:b");
        assertThat(e.getContext().getVariable("text")).isEqualTo("hi from a");
        assertThat(e.getContext().getVariable("signal")).isEqualTo("hello");
    }

    @Test
    void aSignalIsTakenOnceSoNoLaterWaitOfAnyKindSeesItAgain() {
        HarnessExecutor e =
                run(
                        """
                        start Chatty(x = "a") budget { tokens: 1 } -> work["a"]
                        wait any work timeout 5m -> e1
                        wait work["a"] signal "hello" timeout 5m -> again
                        alt (again is ok) { set second = "saw it twice" } else { set second = "once" }
                        """);

        assertThat(e.getContext().getVariable("second")).isEqualTo("once");
    }

    @Test
    void aStickySignalIsReportedOnceUntilItIsSetAgain() {
        HarnessExecutor e =
                run(
                        """
                        start Latch(x = "a") budget { tokens: 1 } -> work["a"]
                        wait any work timeout 5m -> e1
                        wait any work timeout 5m -> e2
                        set kinds = "{e1.kind} {e2.kind}"
                        """);

        assertThat(e.getContext().getVariable("kinds")).isEqualTo("signal timeout");
    }

    // ---- timeout ----

    @Test
    void aTimeoutEventLeavesTheChildrenRunningAndSaysHowManyRemain() {
        HarnessExecutor e =
                run(
                        """
                        start Waiter(x = "a") budget { tokens: 1 } -> work["a"]
                        wait any work timeout 5m -> e1
                        set kind = e1.kind
                        set left = e1.remaining
                        """);

        assertThat(e.getContext().getVariable("kind")).isEqualTo("timeout");
        assertThat(String.valueOf(e.getContext().getVariable("left"))).isEqualTo("1");
        assertThat(String.valueOf(h.onlyChild().get("#cancel").orElseThrow().value()))
                .as("the timeout did not cancel the child: only the end of the parent did")
                .contains("parent ended");
        assertThat(since(h)).isBetween(Duration.ofMinutes(5), Duration.ofMinutes(6));
    }

    @Test
    void aWaitAnyOverNoHandlesReturnsATimeoutEventAtOnceWithNothingRemaining() {
        HarnessExecutor e =
                run(
                        """
                        for each t in q.items max 3 {
                            start Quick(x = t) budget { tokens: 1 } -> work[t]
                        }
                        wait any work timeout 5m -> e1
                        set kind = e1.kind
                        set left = e1.remaining
                        """);

        assertThat(e.getContext().getVariable("kind")).isEqualTo("timeout");
        assertThat(String.valueOf(e.getContext().getVariable("left"))).isEqualTo("0");
        assertThat(since(h)).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void aWaitAnyOnChildrenThatAllSleepSuspendsTheTreeAsAWaitDoes() {
        assertThatThrownBy(
                        () ->
                                run(
                                        """
                                        start Napper(x = "a") budget { tokens: 1 } -> work["a"]
                                        wait any work timeout 5h -> e1
                                        """))
                .isInstanceOfAny(RunSuspended.class, IllegalStateException.class);
    }

    // ---- limits ----

    @Test
    void moreHandlesThanMaxChildrenInOneWaitAnyFailsNamingTheLimit() {
        assertThatThrownBy(
                        () -> {
                            HarnessExecutor e =
                                    h.parent(
                                            "max_children: 1\n" + CHILDREN
                                                    + """
                                                    workflow Main(q) {
                                                        start Quick(x = "a") budget { tokens: 1 } -> work["a"]
                                                        wait work["a"] timeout 1m
                                                        start Quick(x = "b") budget { tokens: 1 } -> work["b"]
                                                        wait work["b"] timeout 1m
                                                        wait any work timeout 1m -> e
                                                    }
                                                    """,
                                            h.controlled());
                            e.executeWorkflow("Main", Map.of("q", "x"));
                        })
                .hasMessageContaining("max_children")
                .hasMessageContaining("1");
    }

    // ---- provenance ----

    @Test
    void anEventCarriesChildTextSoItIsUnchecked() {
        HarnessExecutor e =
                run(
                        """
                        start Chatty(x = "a") budget { tokens: 1 } -> work["a"]
                        wait any work timeout 5m -> e1
                        """);

        assertThat(e.tagOf("e1").level()).isEqualTo(Provenance.UNCHECKED.level());
    }

    // ---- G18: the first return is journaled; a replay returns the same event ----

    @Test
    void aParentRunAgainOverItsJournalsGetsTheSameEventsAndConsumesNothingTwice() {
        String main =
                """
                start Chatty(x = "a") budget { tokens: 1 } -> work["a"]
                start Quick(x = "b") budget { tokens: 1 } -> work["b"]
                wait any work timeout 5m -> e1
                wait any work timeout 5m -> e2
                wait any work timeout 5m -> e3
                set order = "{e1.kind}:{e1.name} {e2.kind}:{e2.name} {e3.kind}:{e3.name}"
                """;
        HarnessExecutor first = run(main);
        int parentEntries = h.journals.get("p").all().size();
        long takes =
                h.journals.entrySet().stream()
                        .filter(en -> !en.getKey().equals("p"))
                        .flatMap(en -> en.getValue().all().keySet().stream())
                        .filter(k -> k.startsWith("#sig.take"))
                        .count();

        HarnessExecutor again = h.parent(CHILDREN + "workflow Main(q) {\n" + main + "\n}\n", h.controlled());
        again.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(again.getContext().getVariable("order")).isEqualTo(first.getContext().getVariable("order"));
        assertThat(h.journals.get("p").all()).as("nothing new was decided").hasSize(parentEntries);
        assertThat(
                        h.journals.entrySet().stream()
                                .filter(en -> !en.getKey().equals("p"))
                                .flatMap(en -> en.getValue().all().keySet().stream())
                                .filter(k -> k.startsWith("#sig.take"))
                                .count())
                .isEqualTo(takes);
    }

    @Test
    void theWinnerIsJournaledWithTheWaitAndSaysWhichChildAndWhichSignal() {
        run(
                """
                start Chatty(x = "a") budget { tokens: 1 } -> work["a"]
                wait any work timeout 5m -> e1
                """);

        var entry = h.journals.get("p").all().values().stream().filter(en -> "wait-any".equals(en.kind())).findFirst().orElseThrow();
        assertThat(String.valueOf(entry.value())).contains("signal").contains("hello").contains("taken");
    }
}
