package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.cost.CostEstimate;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 34a, F4: the worst case of a supervisor counts what it can start and what it pays to
 * judge. A child's budget is a cap on its spending, so the worst case adds that cap for each
 * {@code start} the walk reaches (a loop multiplies it by its {@code max}), and each judged
 * {@code supervise} adds its own budget. The typical walk goes into the child's own steps.
 */
class ChildCostTest extends Lp10Test {

    @TempDir Path folder;

    private static final String BASE =
            """
            agent Researcher { model: "m" system: "You research." }
            evaluator J { model: "m" }
            workflow Research(topic) { delegate "Find {topic}" to Researcher -> notes  return notes }
            """;

    private CostEstimate estimate(String source) {
        return new CostEstimator(null, null)
                .estimate(ReviewFixtures.input(folder, source), ReviewSettings.parse("review.properties", ""));
    }

    private static long worstTokens(CostEstimate e, String workflow) {
        return e.workflows().stream().filter(w -> w.name().equals(workflow)).findFirst().orElseThrow().worst().tokens();
    }

    private static long typicalTokens(CostEstimate e, String workflow) {
        return e.workflows().stream().filter(w -> w.name().equals(workflow)).findFirst().orElseThrow().typical().tokens();
    }

    @Test
    void aStartAddsItsChildsBudgetToTheWorstCaseOfTheParent() {
        CostEstimate none = estimate(BASE + "workflow Main(q) { set x = \"1\" }\n");
        CostEstimate one =
                estimate(BASE + "workflow Main(q) { start Research(topic = q) budget { tokens: 50000 } -> work  wait work timeout 5m -> r }\n");

        assertThat(worstTokens(one, "Main") - worstTokens(none, "Main")).isEqualTo(50_000);
    }

    @Test
    void aStartInABoundedLoopCountsTheChildsBudgetOncePerTurn() {
        CostEstimate e =
                estimate(
                        BASE
                                + """
                                workflow Main(q) {
                                    for each t in q.topics max 4 {
                                        start Research(topic = t.name) budget { tokens: 10000 } -> work[t.name]
                                    }
                                    wait all work timeout 5m -> r
                                }
                                """);

        assertThat(worstTokens(e, "Main")).isEqualTo(40_000);
    }

    @Test
    void aJudgedSuperviseAddsItsOwnBudgetToTheWorstCase() {
        String main =
                "workflow Main(q) { start Research(topic = q) budget { tokens: 1000 } -> work\n"
                        + "supervise work budget { tokens: 7000 } { assert \"on topic\" using J every 2m max 3 { wrap_up } }\n"
                        + "wait work timeout 5m -> r }\n";
        CostEstimate e = estimate(BASE + main);

        assertThat(worstTokens(e, "Main")).isEqualTo(1000 + 7000);
    }

    @Test
    void aSuperviseWithNoJudgeAddsNothing() {
        String main =
                "workflow Main(q) { start Research(topic = q) budget { tokens: 1000 } -> work\n"
                        + "supervise work { when tokens > 500 { wrap_up } }\n"
                        + "wait work timeout 5m -> r }\n";

        assertThat(worstTokens(estimate(BASE + main), "Main")).isEqualTo(1000);
    }

    @Test
    void theTypicalWalkCountsTheChildsOwnModelCallsSoItIsPricedAndNotJustACap() {
        CostEstimate e =
                estimate(BASE + "workflow Main(q) { start Research(topic = q) budget { tokens: 50000 } -> work  wait work timeout 5m -> r }\n");

        long typical = typicalTokens(e, "Main");
        assertThat(typical).as("the child's one delegate, not its whole cap").isGreaterThan(0).isLessThan(50_000);
        assertThat(e.workflows().stream().filter(w -> w.name().equals("Main")).findFirst().orElseThrow().notes())
                .anyMatch(n -> n.contains("child budget"));
    }
}
