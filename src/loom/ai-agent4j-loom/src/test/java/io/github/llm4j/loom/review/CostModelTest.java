package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.loom.review.cost.Assumptions;
import io.github.llm4j.loom.review.cost.CostEstimate;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.cost.Tokens;
import io.github.llm4j.loom.review.cost.Usage;
import io.github.llm4j.loom.review.cost.WorkflowCost;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R10, R12, R13, R15 of spec 09 (§5): what a workflow costs, from the script alone. */
class CostModelTest {

    @TempDir Path dir;

    // "You are helpful" = 15 chars = 5 tokens; "hello world" = 11 chars = 4 tokens; answer 350
    static final String AGENT = "agent A { model: \"m\"  system: \"You are helpful\" }\n";
    static final String CALL = "delegate \"hello world\" to A -> r";
    static final long IN = 9;
    static final long OUT = 350;
    static final long OUT_MAX = 2000;

    CostEstimate estimate(String source) {
        return estimate(source, "", null);
    }

    CostEstimate estimate(String source, String properties, PriceTable prices) {
        return new CostEstimator(prices, prices == null ? null : "prices.properties")
                .estimate(
                        ReviewFixtures.input(dir, source),
                        ReviewSettings.parse("review.properties", properties));
    }

    WorkflowCost main(CostEstimate e) {
        return e.workflows().get(0);
    }

    @Test
    void tokensAreCountedTheWayTheRuntimeGuardCountsThem() {
        for (String text : new String[] {"", "a", "hello world", "x".repeat(1000), "é".repeat(77)})
            assertThat(Tokens.of(text))
                    .as(text.length() + " chars")
                    .isEqualTo(CharsPerTokenEstimator.INSTANCE.completion(text));
    }

    @Test
    void oneDelegateIsOneCallOfItsPromptAndTheAssumedAnswer() {
        WorkflowCost w = main(estimate(AGENT + "workflow Main(q) { " + CALL + "  note \"{r}\" }"));
        assertThat(w.typical().tokensIn()).isEqualTo(IN);
        assertThat(w.typical().tokensOut()).isEqualTo(OUT);
        assertThat(w.typical().calls()).isEqualTo(1);
        assertThat(w.worst().calls()).isEqualTo(1);
        assertThat(w.worst().tokensIn()).isEqualTo(IN);
        assertThat(w.worst().tokensOut()).isEqualTo(OUT_MAX);
        assertThat(w.unbounded()).isFalse();
        assertThat(w.steps()).hasSize(1);
        assertThat(w.steps().get(0).label()).isEqualTo("delegate A");
        assertThat(w.steps().get(0).model()).isEqualTo("m");
    }

    @Test
    void aVariableInThePromptAddsTheAssumedSize() {
        WorkflowCost w =
                main(
                        estimate(
                                AGENT
                                        + "workflow Main(q) { delegate \"hello {q}\" to A -> r  note \"{r}\" }"));
        // "hello " = 6 chars = 2 tokens, plus 100 for {q}, plus 5 for the system prompt
        assertThat(w.typical().tokensIn()).isEqualTo(5 + 2 + 100);
    }

    @Test
    void aLoopTurnsAssumedTimesTypicallyAndItsMaxAtMost() {
        WorkflowCost w =
                main(
                        estimate(
                                AGENT
                                        + "workflow Main(q) { loop until (r == \"y\") max 10 { "
                                        + CALL
                                        + " } }"));
        assertThat(w.typical().calls()).isEqualTo(3);
        assertThat(w.worst().calls()).isEqualTo(10);
        assertThat(w.unbounded()).isFalse();
        WorkflowCost small =
                main(
                        estimate(
                                AGENT
                                        + "workflow Main(q) { loop until (r == \"y\") max 2 { "
                                        + CALL
                                        + " } }"));
        assertThat(small.typical().calls()).isEqualTo(2);
    }

    @Test
    void aLoopWithNoMaxIsUnboundedAndGivesNoMadeUpWorstCase() {
        WorkflowCost w =
                main(
                        estimate(
                                AGENT
                                        + "workflow Main(q) { loop until (r == \"y\") { "
                                        + CALL
                                        + " } }"));
        assertThat(w.unbounded()).isTrue();
        assertThat(w.typical().calls()).isEqualTo(3);
        assertThat(w.steps().get(0).worstRuns()).isEqualTo(-1);
        assertThat(w.steps().get(0).worst()).isNull();
    }

    @Test
    void forEachUsesTheItemAssumptionsAndTheyCanBeOverridden() {
        String src = AGENT + "workflow Main(docs) { for each d in docs { " + CALL + " } }";
        WorkflowCost w = main(estimate(src));
        assertThat(w.typical().calls()).isEqualTo(5);
        assertThat(w.worst().calls()).isEqualTo(50);
        CostEstimate e =
                estimate(src, "assume.foreach.items = 20\nassume.foreach.max = 100\n", null);
        assertThat(main(e).typical().calls()).isEqualTo(20);
        assertThat(main(e).worst().calls()).isEqualTo(100);
        assertThat(e.assumptions()).containsEntry("assume.foreach.items", 20L);
    }

    @Test
    void retriesRaiseTheWorstCaseOnly() {
        WorkflowCost w =
                main(
                        estimate(
                                AGENT
                                        + "workflow Main(q) { delegate \"hello world\" to A -> r retry 2 backoff 1s timeout 30s }"));
        assertThat(w.typical().calls()).isEqualTo(1);
        assertThat(w.worst().calls()).isEqualTo(3);
    }

    @Test
    void anAltCostsTheAverageOfItsWaysTypicallyAndTheDearerOneAtWorst() {
        WorkflowCost w =
                main(
                        estimate(
                                AGENT
                                        + "workflow Main(q) { alt (q == \"a\") { "
                                        + CALL
                                        + " } else { note \"no\" } }"));
        assertThat(w.typical().calls()).isEqualTo(1); // ceil(1 / 2)
        assertThat(w.typical().tokensOut()).isEqualTo((OUT + 1) / 2);
        assertThat(w.worst().calls()).isEqualTo(1);
        assertThat(w.worst().tokensOut()).isEqualTo(OUT_MAX);
    }

    @Test
    void anAgentWithToolsMakesRoundsAndTheContextGrows() {
        String src =
                "agent A { model: \"m\"  system: \"You are helpful\"  tools: [web_search] }\n"
                        + "workflow Main(q) { "
                        + CALL
                        + " }";
        WorkflowCost w = main(estimate(src));
        // 3 rounds typical: 3 x base + (350 + 300) x (0 + 1 + 2)
        assertThat(w.typical().calls()).isEqualTo(3);
        assertThat(w.typical().tokensIn()).isEqualTo(3 * IN + 650 * 3);
        assertThat(w.typical().tokensOut()).isEqualTo(3 * OUT);
        // 10 rounds worst unless the agent sets max_iterations
        assertThat(w.worst().calls()).isEqualTo(10);
        assertThat(w.worst().tokensIn()).isEqualTo(10 * IN + (OUT_MAX + 300) * 45);
        assertThat(w.worst().tokensOut()).isEqualTo(10 * OUT_MAX);
    }

    @Test
    void broadcastCostsEveryTarget() {
        String src =
                AGENT
                        + "agent B { model: \"n\"  system: \"s\" }\n"
                        + "workflow Main(q) { broadcast \"hello world\" to [A, B] -> rs }";
        WorkflowCost w = main(estimate(src));
        assertThat(w.typical().calls()).isEqualTo(2);
        assertThat(w.typical().byModel()).containsOnlyKeys("m", "n");
    }

    @Test
    void aCalledWorkflowIsCountedWhereItIsCalledAndRecursionOnce() {
        String src =
                AGENT
                        + "workflow Helper(q) { "
                        + CALL
                        + " }\n"
                        + "workflow Main(q) { call Helper(q = q) -> h  call Helper(q = q) -> g }\n"
                        + "workflow Loopy(q) { call Loopy(q = q) -> x }\n";
        CostEstimate e = estimate(src);
        assertThat(e.workflows().get(1).typical().calls()).isEqualTo(2);
        WorkflowCost loopy = e.workflows().get(2);
        assertThat(loopy.typical().calls()).isZero();
        assertThat(loopy.notes()).anyMatch(n -> n.contains("calls itself"));
    }

    @Test
    void rewindAllowsTheWholeWorkflowToRunAgainAtMostThatOftenInTheWorstCase() {
        String src =
                AGENT
                        + "workflow Main(q) {\n checkpoint first\n "
                        + CALL
                        + "\n rewind to first when (r == \"bad\") at most 2 times\n}";
        WorkflowCost w = main(estimate(src));
        assertThat(w.typical().calls()).isEqualTo(1);
        assertThat(w.worst().calls()).isEqualTo(3);
        assertThat(w.notes()).anyMatch(n -> n.contains("rewind"));
    }

    @Test
    void moneyComesFromThePriceTableAndAnUnpricedModelIsNamedNotCountedAsFree() {
        PriceTable prices =
                PriceTable.of(
                        Map.of(
                                "m",
                                new PriceTable.Price(new BigDecimal("1"), new BigDecimal("2"))));
        CostEstimate e = estimate(AGENT + "workflow Main(q) { " + CALL + " }", "", prices);
        Usage.Money m = e.typicalMoney(main(e));
        // 9 in x $1/M + 350 out x $2/M
        assertThat(m.amount()).isEqualByComparingTo("0.000709");
        assertThat(m.complete()).isTrue();
        CostEstimate none = estimate(AGENT + "workflow Main(q) { " + CALL + " }");
        assertThat(none.typicalMoney(main(none)).unpriced()).containsExactly("m");
        assertThat(none.unpriced()).containsExactly("m");
    }

    @Test
    void aBudgetCapsTheWorstCaseAndStandsInForAnUnboundedOne() {
        PriceTable prices =
                PriceTable.of(
                        Map.of(
                                "m",
                                new PriceTable.Price(new BigDecimal("1"), new BigDecimal("2"))));
        String bounded =
                "budget { cost: \"$0.002\" }\n"
                        + AGENT
                        + "workflow Main(q) { loop until (r == \"y\") max 10 { "
                        + CALL
                        + " } }";
        CostEstimate e = estimate(bounded, "", prices);
        assertThat(e.cappedWorst(main(e))).isEqualByComparingTo("0.002");
        assertThat(e.capped(main(e))).isTrue();
        String unbounded =
                "budget { cost: \"$0.5\" }\n"
                        + AGENT
                        + "workflow Main(q) { loop until (r == \"y\") { "
                        + CALL
                        + " } }";
        CostEstimate u = estimate(unbounded, "", prices);
        assertThat(u.cappedWorst(main(u))).isEqualByComparingTo("0.5");
        CostEstimate free =
                estimate(
                        AGENT + "workflow Main(q) { loop until (r == \"y\") { " + CALL + " } }",
                        "",
                        prices);
        assertThat(free.cappedWorst(main(free))).isNull();
    }

    @Test
    void anUnknownOrMalformedAssumptionIsRefusedWithTheNearestName() {
        assertThatThrownBy(
                        () ->
                                estimate(
                                        AGENT + "workflow Main(q) { note \"x\" }",
                                        "assume.loop.iteration = 2\n",
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("did you mean 'assume.loop.iterations'");
        assertThatThrownBy(
                        () ->
                                estimate(
                                        AGENT + "workflow Main(q) { note \"x\" }",
                                        "assume.loop.iterations = lots\n",
                                        null))
                .hasMessageContaining("takes a whole number");
    }

    @Test
    void theSameInputsAlwaysGiveTheSameNumbers() {
        String src = AGENT + "workflow Main(docs) { for each d in docs { " + CALL + " } }";
        assertThat(main(estimate(src)).typical().byModel())
                .isEqualTo(main(estimate(src)).typical().byModel());
    }

    @Test
    void everyAssumptionHasADefaultAndAMeaning() {
        for (Assumptions.Def d : Assumptions.DEFS) {
            assertThat(d.key()).startsWith("assume.");
            assertThat(d.meaning()).isNotBlank();
        }
    }
}
