package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.loom.review.cost.CostEstimate;
import io.github.llm4j.loom.review.cost.StepCost;
import io.github.llm4j.loom.review.cost.Usage;
import io.github.llm4j.loom.review.cost.WorkflowCost;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.report.JsonReport;
import io.github.llm4j.loom.review.report.TextReport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 22 R11: the cost block says what it assumed (runs and tokens per step, typical and worst
 * case), prints dollars when the price is known, and says when the run's cap will stop it before
 * the worst case.
 */
class CostBlockSaysWhatItAssumedTest {

    private static WorkflowCost workflow(boolean unbounded) {
        StepCost writer =
                new StepCost(
                        "Main", 7, "delegate Writer", "gpt-4o-mini",
                        Usage.calls("gpt-4o-mini", 3, 3_000, 900),
                        Usage.calls("gpt-4o-mini", 8, 32_000, 16_000),
                        0, 3, 8);
        return new WorkflowCost(
                "Main",
                Usage.calls("gpt-4o-mini", 3, 3_000, 900),
                Usage.calls("gpt-4o-mini", 8, 120_000, 59_000),
                unbounded,
                List.of(writer),
                List.of());
    }

    private static ReviewReport report(CostEstimate cost) {
        return new ReviewReport("main.loom", List.of(), List.of(), List.of(), List.of(Lens.COST), List.of(), cost);
    }

    private static CostEstimate estimate(Long budgetTokens, boolean priced, boolean unbounded) {
        PriceTable prices = priced ? PriceTable.of(Map.of("gpt-4o-mini", new PriceTable.Price(new BigDecimal("0.15"), new BigDecimal("0.60")))) : null;
        return new CostEstimate(
                List.of(workflow(unbounded)), Map.of("assume.output.tokens", 350L), prices,
                priced ? "prices.txt" : null, null, budgetTokens);
    }

    @Test
    void eachStepSaysHowOftenItRunsAndWhatEachRunCosts() {
        String text = TextReport.write(report(estimate(null, true, false)));

        assertThat(text).contains("Assumed per step");
        assertThat(text).contains("delegate Writer").contains("line 7");
        assertThat(text).contains("3 runs").contains("at most 8");
        assertThat(text).contains("1,300 tokens a run").contains("6,000 tokens a run");
    }

    @Test
    void dollarsAreShownWhenEveryModelIsPriced() {
        String text = TextReport.write(report(estimate(null, true, false)));

        assertThat(text).containsPattern("typical \\$0\\.0\\d+ / run");
    }

    @Test
    void anUnpricedModelIsNamedAndTheFiguresAreTokens() {
        String text = TextReport.write(report(estimate(null, false, false)));

        assertThat(text).contains("no price for gpt-4o-mini").contains("tokens");
    }

    @Test
    void whenTheCapIsBelowTheWorstCaseTheReportSaysTheRunWillBeStoppedFirst() {
        String text = TextReport.write(report(estimate(150_000L, true, false)));

        assertThat(text).contains("the cap, 150,000 tokens, will stop the run before the worst case, 179,000 tokens");
        assertThat(text).contains("expect cut results");
    }

    @Test
    void anUnboundedWorstCaseIsStoppedByTheCapToo() {
        String text = TextReport.write(report(estimate(150_000L, true, true)));

        assertThat(text).contains("the cap, 150,000 tokens, will stop the run before the worst case").contains("no bound");
    }

    @Test
    void aCapAboveTheWorstCaseSaysNothingAboutBiting() {
        String text = TextReport.write(report(estimate(500_000L, true, false)));

        assertThat(text).doesNotContain("will stop the run");
    }

    @Test
    void theJsonCarriesTheSamePerStepAssumptionsAndTheCapFlag() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(JsonReport.write(report(estimate(150_000L, true, false))));

        var wf = json.path("cost").path("workflows").get(0);
        assertThat(wf.path("capStopsBeforeWorstCase").asBoolean()).isTrue();
        assertThat(wf.path("steps").get(0).path("typicalRuns").asLong()).isEqualTo(3);
        assertThat(wf.path("steps").get(0).path("worstRuns").asLong()).isEqualTo(8);
    }
}
