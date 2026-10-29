package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What limit a model call is sent with when the agent chose none. A fixed 1,024 once clipped
 * articles mid-sentence while the run reported success; the limit is now the model's known maximum,
 * bounded by what the budget still affords.
 */
class OutputLimitDecisionTest {
    private static BudgetSet tokens(long limit) {
        return BudgetSet.of(Budget.builder().name("run").tokens(limit).build());
    }

    @Test
    void anAgentsOwnLimitIsKept() {
        assertThat(tokens(100_000).reserve(100, 500L, 8_000L, null, 0).output()).isEqualTo(500L);
    }

    @Test
    void withNoLimitChosenTheModelsKnownMaximumIsUsed() {
        assertThat(tokens(100_000).reserve(100, null, 8_000L, null, 0).output()).isEqualTo(8_000L);
    }

    @Test
    void anUnknownModelGetsTheDocumentedDefaultNotOneThousand() {
        assertThat(tokens(100_000).reserve(100, null, null, null, 0).output())
                .isEqualTo(BudgetSet.DEFAULT_OUTPUT)
                .isGreaterThan(1_024L);
    }

    @Test
    void theBudgetBoundsTheLimitWhenItAffordsLess() {
        assertThat(tokens(3_100).reserve(100, null, 8_000L, null, 0).output()).isEqualTo(3_000L);
    }

    @Test
    void aBudgetOfCallsAloneLeavesTheAnswerUncapped() {
        BudgetSet calls = BudgetSet.of(Budget.builder().name("run").calls(10).build());

        assertThat(calls.reserve(100, null, 8_000L, null, 0).output()).isNull();
    }

    @Test
    void aBigModelMaximumDoesNotStarveAParallelSibling() {
        BudgetSet set = tokens(200_000);

        var first = set.reserve(100, null, 128_000L, null, 0);
        var second = set.reserve(100, null, 128_000L, null, 0);

        assertThat(first.output()).isEqualTo(128_000L);
        assertThat(second.output()).isGreaterThan(BudgetSet.MIN_OUTPUT);
    }

    @Test
    void theOldTwoArgumentFormStillWorksForAnUnknownModel() {
        assertThat(tokens(100_000).reserve(100, null, null).output())
                .isEqualTo(BudgetSet.DEFAULT_OUTPUT);
    }
}
