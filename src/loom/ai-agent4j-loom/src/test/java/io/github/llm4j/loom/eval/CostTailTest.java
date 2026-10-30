package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 19 R13: the efficiency numbers that matter. The median hides the tail, so the report gives
 * the p95 cost, the runaway rate (runs a limit stopped) and the graceful-termination rate (limit
 * stops that still left an output behind).
 */
class CostTailTest {

    private static ScenarioResult run(String id, String cost, String error, String output) {
        List<AgentFacts> agents =
                cost == null ? List.of() : List.of(new AgentFacts("A", 1, 10, 10, new BigDecimal(cost), 0, 0));
        return new ScenarioResult("A", "agent", "f", id, id, error == null ? Status.PASS : Status.FAIL, List.of(), cost == null ? null : new BigDecimal(cost), error, "in", output, List.of(), List.of(), agents);
    }

    private static List<ScenarioResult> costs(String... values) {
        List<ScenarioResult> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) out.add(run("c" + i, values[i], null, "answer"));
        return out;
    }

    @Test
    void medianAndP95AreReadFromTheCostOfEachRun() {
        CostTail tail = CostTail.of(costs("1", "2", "3", "4", "5", "6", "7", "8", "9", "100"));

        assertThat(tail.median()).isEqualByComparingTo("5.5");
        assertThat(tail.p95()).isEqualByComparingTo("100");
        assertThat(tail.known()).isEqualTo(10);
    }

    @Test
    void p95IsTheNearestRankSoOneRunawayInTwentyIsTheTail() {
        List<ScenarioResult> twenty = new ArrayList<>(costs("1", "1", "1", "1", "1", "1", "1", "1", "1", "1", "1", "1", "1", "1", "1", "1", "1", "1", "1"));
        twenty.add(run("big", "50", null, "answer"));

        assertThat(CostTail.of(twenty).p95()).isEqualByComparingTo("1");
        twenty.add(run("big2", "60", null, "answer"));
        assertThat(CostTail.of(twenty).p95()).isEqualByComparingTo("50");
    }

    @Test
    void aRunWithNoKnownCostIsLeftOutNotCountedAsZero() {
        List<ScenarioResult> mixed = new ArrayList<>(costs("2", "4"));
        mixed.add(run("unknown", null, null, "answer"));

        CostTail tail = CostTail.of(mixed);

        assertThat(tail.known()).isEqualTo(2);
        assertThat(tail.median()).isEqualByComparingTo("3");
    }

    @Test
    void noKnownCostMeansNoMedianAndNoTail() {
        CostTail tail = CostTail.of(List.of(run("x", null, null, "answer")));

        assertThat(tail.median()).isNull();
        assertThat(tail.p95()).isNull();
    }

    @Test
    void aRunALimitStoppedIsARunawayAndOneThatKeptAnOutputIsGraceful() {
        List<ScenarioResult> results = new ArrayList<>(costs("1", "1"));
        results.add(run("r1", "9", "stopped by a limit: the budget ran out", "half an answer"));
        results.add(run("r2", "9", "stopped by a limit: the budget ran out", null));

        CostTail tail = CostTail.of(results);

        assertThat(tail.runaway()).isEqualTo(2);
        assertThat(tail.graceful()).isEqualTo(1);
        assertThat(tail.runs()).isEqualTo(4);
    }

    @Test
    void otherErrorsAreNotRunaways() {
        List<ScenarioResult> results = List.of(run("e", "1", "agent produced no output", null));

        assertThat(CostTail.of(results).runaway()).isZero();
    }

    @Test
    void theLinesSayTheTailInPlainWordsAndNameWhatIsUnknown() {
        List<String> lines = CostTail.of(costs("1", "2", "3")).lines(java.util.function.Function.identity().andThen(Object::toString)::apply);

        assertThat(String.join("\n", lines)).contains("median per case").contains("p95 per case").contains("runaway");
        assertThat(String.join("\n", CostTail.of(List.of(run("x", null, null, "a"))).lines(Object::toString)))
                .contains("unknown without prices");
    }
}
