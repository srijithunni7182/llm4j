package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.cost.CostRules;
import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** LC04, LC05, LC06 of spec 09 (§6.3). */
class CostRulesTest {

    @TempDir Path dir;

    static final PriceTable PRICES =
            PriceTable.of(
                    Map.of(
                            "m",
                            new PriceTable.Price(
                                    new BigDecimal("1000000"), new BigDecimal("1000000"))));

    static final String AGENT = "agent A { model: \"m\"  system: \"You are helpful\" }\n";
    static final String CALL = "delegate \"hello world\" to A -> r";

    List<Finding> run(String source, String properties, PriceTable prices) {
        List<Finding> out = new ArrayList<>();
        new CostRules(new CostEstimator(prices, "p"))
                .collect(
                        ReviewFixtures.input(dir, source),
                        ReviewSettings.parse("review.properties", properties),
                        new FindingSource.Sink() {
                            public void finding(Finding f) {
                                out.add(f);
                            }

                            public void note(io.github.llm4j.loom.review.model.Note n) {}
                        });
        return out;
    }

    @Test
    void aMoneyBudgetBelowTheTypicalCostIsLc04() {
        // $1 per token: a run of 609 tokens costs $609
        List<Finding> f =
                run(
                        "budget { cost: \"$5.00\" }\n"
                                + AGENT
                                + "workflow Main(q) { "
                                + CALL
                                + " }",
                        "",
                        PRICES);
        assertThat(f).hasSize(1);
        assertThat(f.get(0).rule()).isEqualTo("LC04");
        assertThat(f.get(0).severity()).isEqualTo(Severity.MEDIUM);
        assertThat(f.get(0).title())
                .contains("typically costs $609.00")
                .contains("budget is $5.00");
    }

    @Test
    void aBudgetAboveTheTypicalCostIsQuiet() {
        assertThat(
                        run(
                                "budget { cost: \"$5000.00\" }\n"
                                        + AGENT
                                        + "workflow Main(q) { "
                                        + CALL
                                        + " }",
                                "",
                                PRICES))
                .isEmpty();
    }

    @Test
    void aTokenBudgetBelowTheTypicalUseIsLc04WithoutAnyPrices() {
        List<Finding> f =
                run(
                        "budget { tokens: 100 }\n" + AGENT + "workflow Main(q) { " + CALL + " }",
                        "",
                        null);
        assertThat(f).extracting(Finding::rule).containsExactly("LC04");
        assertThat(f.get(0).title()).contains("609 tokens");
    }

    @Test
    void oneStepThatIsMostOfTheCostIsLc05AndTheShareCanBeChanged() {
        String src =
                AGENT
                        + "agent B { model: \"m\"  system: \"s\"  tools: [web_search] }\n"
                        + "workflow Main(q) { delegate \"hello world\" to A -> r  delegate \"hello world\" to B -> s }";
        List<Finding> f = run(src, "", null);
        assertThat(f).extracting(Finding::rule).containsExactly("LC05");
        assertThat(f.get(0).title())
                .contains("delegate B")
                .contains("% of the typical cost of Main");
        assertThat(f.get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(run(src, "cost.step.share = 99\n", null)).isEmpty();
    }

    @Test
    void aWorkflowWithOneStepHasNoDominantStep() {
        assertThat(run(AGENT + "workflow Main(q) { " + CALL + " }", "", null)).isEmpty();
    }

    @Test
    void anExpensiveRetriedStepIsLc06OnlyWithPrices() {
        String src = AGENT + "workflow Main(q) { " + CALL + " retry 2 backoff 1s timeout 30s }";
        List<Finding> f = run(src, "", PRICES);
        assertThat(f).extracting(Finding::rule).containsExactly("LC06");
        assertThat(f.get(0).title()).contains("retries 2 times");
        assertThat(run(src, "cost.retry.limit = 100000\n", PRICES)).isEmpty();
        assertThat(run(src, "", null)).isEmpty();
    }

    @Test
    void theCostLensCanBeTurnedOff() {
        assertThat(
                        run(
                                "budget { tokens: 100 }\n"
                                        + AGENT
                                        + "workflow Main(q) { "
                                        + CALL
                                        + " }",
                                "lenses = -cost\n",
                                null))
                .isEmpty();
    }
}
