package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.budget.fixtures.FixedEstimator;
import io.github.llm4j.budget.fixtures.ScriptedLLMClient;
import io.github.llm4j.model.LLMRequest;
import org.junit.jupiter.api.Test;

/** With a token budget and no limit chosen, a call is not clipped to 1,024 tokens. */
class NoSilentClipTest {
    private static long sentLimit(String model, long budgetTokens) {
        ScriptedLLMClient fake = new ScriptedLLMClient();
        var client =
                BudgetedLLMClient.builder(fake)
                        .budget(Budget.builder().tokens(budgetTokens).build())
                        .estimator(FixedEstimator.INSTANCE)
                        .build();
        client.chat(LLMRequest.builder().model(model).addUserMessage("write").build());
        return fake.requests().get(0).getMaxTokens();
    }

    @Test
    void aKnownModelsMaximumIsSentWhenTheBudgetAffordsIt() {
        assertThat(sentLimit("claude-opus-5-5", 1_000_000)).isEqualTo(128_000);
    }

    @Test
    void anUnknownModelIsNotClippedToOneThousandEither() {
        assertThat(sentLimit("some-new-model", 1_000_000)).isEqualTo(BudgetSet.DEFAULT_OUTPUT);
    }

    @Test
    void theBudgetStillBoundsTheLimit() {
        assertThat(sentLimit("claude-opus-5-5", 5_000)).isLessThan(5_000);
    }
}
