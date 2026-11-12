package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Where a reservation stops (phase 34a, G26): exactly the budget is accepted and one more is refused,
 * for tokens, calls and cost, for the child's own cap, for the judge's, and with another live child
 * holding part back. The children never run (a controlled scheduler that is not advanced), so every
 * cap is held in full.
 */
class ChildReservationEdgeTest {

    private final ChildHarness h = new ChildHarness();

    private HarnessExecutor parent(String header, String body) {
        return h.parent(
                header
                        + "evaluator J { model: \"m\" }\nworkflow Child(x) { run Fine() -> r  return r }\n"
                        + "workflow Main(q) {\n" + body + "\n}\n",
                new ControlledChildScheduler());
    }

    private void run(String header, String body) {
        parent(header, body).executeWorkflow("Main", Map.of("q", "x"));
    }

    @Test
    void aTokenCapEqualToWhatIsLeftIsAcceptedAndOneMoreIsRefused() {
        run("budget { tokens: 100 }\n", "start Child(x = q) budget { tokens: 100 } -> work");
        assertThatThrownBy(() -> run("budget { tokens: 100 }\n", "start Child(x = q) budget { tokens: 101 } -> work"))
                .hasMessageContaining("cannot reserve 101 tokens")
                .hasMessageContaining("has 100 left");
    }

    @Test
    void aCallCapEqualToWhatIsLeftIsAcceptedAndOneMoreIsRefused() {
        run("budget { calls: 5 }\n", "start Child(x = q) budget { calls: 5 } -> work");
        assertThatThrownBy(() -> run("budget { calls: 5 }\n", "start Child(x = q) budget { calls: 6 } -> work"))
                .hasMessageContaining("cannot reserve 6 calls");
    }

    @Test
    void aCostCapEqualToWhatIsLeftIsAcceptedAndALittleMoreIsRefused() {
        run("budget { cost: \"$1.00\" }\n", "start Child(x = q) budget { cost: \"$1.00\" } -> work");
        assertThatThrownBy(() -> run("budget { cost: \"$1.00\" }\n", "start Child(x = q) budget { cost: \"$1.01\" } -> work"))
                .hasMessageContaining("cannot reserve $1.01");
    }

    @Test
    void aLiveChildHoldsBackItsWholeCapInTokensCallsAndCost() {
        String header = "budget { tokens: 100 calls: 10 cost: \"$1.00\" }\n";
        assertThatThrownBy(
                        () -> run(header, "start Child(x = q) budget { tokens: 60 } -> a\nstart Child(x = q) budget { tokens: 41 } -> b"))
                .hasMessageContaining("could still spend 60");
        assertThatThrownBy(
                        () -> run(header, "start Child(x = q) budget { calls: 6 } -> a\nstart Child(x = q) budget { calls: 5 } -> b"))
                .hasMessageContaining("could still spend 6");
        assertThatThrownBy(
                        () ->
                                run(
                                        header,
                                        "start Child(x = q) budget { cost: \"$0.60\" } -> a\nstart Child(x = q) budget { cost: \"$0.41\" } -> b"))
                .hasMessageContaining("cannot reserve $0.41");
        // and exactly what is left is accepted
        run(header, "start Child(x = q) budget { tokens: 60 } -> a\nstart Child(x = q) budget { tokens: 40 } -> b");
        run(header, "start Child(x = q) budget { calls: 6 } -> a\nstart Child(x = q) budget { calls: 4 } -> b");
        run(header, "start Child(x = q) budget { cost: \"$0.60\" } -> a\nstart Child(x = q) budget { cost: \"$0.40\" } -> b");
    }

    @Test
    void aLiveChildsJudgeBudgetIsHeldBackInCallsAndCostToo() {
        String judge = "assert \"on topic\" using J every 2m max 2 { wrap_up }";
        String header = "budget { tokens: 1000 calls: 10 cost: \"$1.00\" }\n";
        String first =
                "start Child(x = q) budget { tokens: 10 calls: 1 cost: \"$0.10\" } -> a\nsupervise a budget { calls: 4 cost: \"$0.30\" } { " + judge + " }\n";

        assertThatThrownBy(() -> run(header, first + "start Child(x = q) budget { calls: 6 } -> b"))
                .hasMessageContaining("cannot reserve 6 calls")
                .hasMessageContaining("could still spend 5");
        assertThatThrownBy(() -> run(header, first + "start Child(x = q) budget { cost: \"$0.61\" } -> b"))
                .hasMessageContaining("cannot reserve $0.61");
        run(header, first + "start Child(x = q) budget { calls: 5 cost: \"$0.60\" } -> b");
    }

    @Test
    void aStartAndItsPairedJudgeAreCheckedTogetherInCallsAndCost() {
        String judge = "assert \"on topic\" using J every 2m max 2 { wrap_up }";
        String header = "budget { calls: 10 cost: \"$1.00\" }\n";

        assertThatThrownBy(
                        () ->
                                run(
                                        header,
                                        "start Child(x = q) budget { calls: 6 } -> a\nsupervise a budget { calls: 5 } { " + judge + " }"))
                .hasMessageContaining("cannot reserve 11 calls (the child's cap and its judge's)");
        assertThatThrownBy(
                        () ->
                                run(
                                        header,
                                        "start Child(x = q) budget { cost: \"$0.60\" } -> a\nsupervise a budget { cost: \"$0.41\" } { " + judge + " }"))
                .hasMessageContaining("cannot reserve $1.01 (the child's cap and its judge's)");
        run(header, "start Child(x = q) budget { calls: 6 } -> a\nsupervise a budget { calls: 4 } { " + judge + " }");
    }

    @Test
    void aBudgetThatIsNotCappedInAKindDoesNotRefuseAStartThatAsksForThatKind() {
        HarnessExecutor e = parent("budget { tokens: 100 }\n", "start Child(x = q) budget { calls: 1000 cost: \"$99.00\" } -> work");
        e.executeWorkflow("Main", Map.of("q", "x"));
        assertThat(e.getContext().getVariable("work")).isNotNull();
    }
}
