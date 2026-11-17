package io.github.llm4j.examples.expensedesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.loom.cli.WeaveCLI;
import org.junit.jupiter.api.Test;

/**
 * Evaluates the workflow with weave eval. The first test is free: it runs the wiring on a
 * mock model. The second spends money, so it runs only when asked (-Deval.real=true), under
 * a cap (-Deval.maxCost=<amount> with -Deval.prices=<file>, else a token cap), and prints
 * the report. A mock run proves wiring, not quality.
 */
class ExpenseDeskEvalTest {

    private static final String SCRIPT = "expense.loom";

    @Test
    void theWiringRunsOnAMockModelAtNoCost() {
        assertThat(WeaveCLI.run("eval", SCRIPT, "--mock")).isZero();
    }

    @Test
    void aRealEvaluationRunsOnlyWhenAskedAndStaysUnderACap() {
        assumeTrue(Boolean.getBoolean("eval.real"), "a real evaluation spends money: run with -Deval.real=true");
        String prices = System.getProperty("eval.prices");
        String[] cap =
                prices != null
                        ? new String[] {"--max-cost", System.getProperty("eval.maxCost", "0.50"), "--prices", prices}
                        : new String[] {"--max-tokens", System.getProperty("eval.maxTokens", "200000")};
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of("eval", SCRIPT, "--yes"));
        args.addAll(java.util.List.of(cap));
        assertThat(WeaveCLI.run(args.toArray(String[]::new))).as("weave eval exit code (1: a case failed, 2: a problem)").isZero();
    }
}
