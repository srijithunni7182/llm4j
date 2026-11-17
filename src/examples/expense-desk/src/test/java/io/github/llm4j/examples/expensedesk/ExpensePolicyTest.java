package io.github.llm4j.examples.expensedesk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class ExpensePolicyTest {

    private final ExpensePolicy policy = new ExpensePolicy();

    @ParameterizedTest
    @CsvSource({
        "42.50, USD, travel, approved",
        "75, USD, other, approved",
        "75.01, USD, other, needs_approval",
        "1200, EUR, equipment, needs_approval",
        "75, USD, meals, approved",
        "68, EUR, travel, approved",
        "69, EUR, travel, needs_approval",
    })
    void decidesByTheAmountInDollars(double amount, String currency, String category, String outcome) {
        assertThat(policy.decide(amount, currency, category).outcome()).isEqualTo(outcome);
    }

    @ParameterizedTest
    @CsvSource({
        "75.01, USD, meals, over the 75 USD limit for meals",
        "2000, EUR, equipment, over the 2000 USD limit for equipment",
        "300, OTHER, other, claims in OTHER are not paid",
        "300, GBP, other, claims in GBP are not paid",
        "8000, INR, meals, over the 75 USD limit for meals",
        "10, USD, gifts, there is no rule for the category gifts",
        "0, USD, travel, more than zero",
        "-5, USD, travel, more than zero",
        "NaN, USD, travel, more than zero",
    })
    void refusesWithAReason(double amount, String currency, String category, String reason) {
        TaskResult verdict = policy.decide(amount, currency, category);

        assertThat(verdict.outcome()).isEqualTo("rejected");
        assertThat(verdict.reason()).contains(reason);
    }

    @Test
    void neverPaysWithoutAPersonWhatSomeCategoryWouldRefuse() {
        // The category is read by a model, so the automatic limit must hold under every category.
        for (String category : ExpensePolicy.categories()) {
            assertThat(ExpensePolicy.AUTOMATIC_LIMIT_USD).isLessThanOrEqualTo(ExpensePolicy.limitOf(category));
            double justOver = ExpensePolicy.AUTOMATIC_LIMIT_USD.doubleValue() + 0.01;
            assertThat(policy.decide(justOver, "USD", category).outcome()).isNotEqualTo("approved");
        }
    }

    @Test
    void isPlainCodeWithNoEffect() {
        assertThat(policy.effect()).isEqualTo(TaskEffect.NONE);
        assertThat(policy.usesModel()).isFalse();
        assertThat(policy.getName()).isEqualTo("ExpensePolicy");
    }
}
