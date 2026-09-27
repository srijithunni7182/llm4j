package io.github.llm4j.budget.fixtures;

import io.github.llm4j.LLMClient;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetSet;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.model.LLMRequest;
import java.math.BigDecimal;
import java.util.Map;

/** Helpers for the verification plan's "standard call": prompt 100 + output 50 = 150 tokens, $0.0002. */
public final class Standard {

    public static final PriceTable PRICES = PriceTable.of(Map.of("test/model",
            new PriceTable.Price(new BigDecimal("1.00"), new BigDecimal("2.00"))));

    private Standard() { }

    public static LLMRequest request() {
        return LLMRequest.builder().model("test/model").addUserMessage("hello").build();
    }

    /** A budgeted client over {@code delegate} with the standard estimator, per-call cap 50 and test prices. */
    public static BudgetedLLMClient client(LLMClient delegate, Budget... budgets) {
        BudgetSet set = BudgetSet.of(budgets);
        return BudgetedLLMClient.builder(delegate).budgets(() -> set).estimator(FixedEstimator.INSTANCE)
                .perCallCap(50).prices(PRICES).model("test/model").build();
    }
}
