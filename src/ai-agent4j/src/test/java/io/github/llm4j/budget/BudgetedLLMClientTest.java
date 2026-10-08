package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.budget.fixtures.FailingClient;
import io.github.llm4j.budget.fixtures.FixedEstimator;
import io.github.llm4j.budget.fixtures.GreedyClient;
import io.github.llm4j.budget.fixtures.NoUsageClient;
import io.github.llm4j.budget.fixtures.ScriptedLLMClient;
import io.github.llm4j.budget.fixtures.Standard;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Verification plan, Requirements 2 and 3 (V2.1–V2.6, V2.8, V3.1, V3.4-at-client, V3.5). */
class BudgetedLLMClientTest {

    @Test
    void v2_1_theCallThatWontFitNeverReachesTheModel() {
        Budget b = Budget.builder().tokens(1000).build();
        ScriptedLLMClient model = new ScriptedLLMClient();
        var client = Standard.client(model, b);
        for (int i = 0; i < 6; i++) client.chat(Standard.request());
        assertThatThrownBy(() -> client.chat(Standard.request())).isInstanceOf(BudgetExceeded.class);
        assertThat(model.calls()).isEqualTo(6);
        assertThat(b.spent().tokens()).isEqualTo(900);
    }

    @Test
    void v2_2_outputIsCappedToWhatIsLeft() {
        ScriptedLLMClient model = new ScriptedLLMClient();
        var client = BudgetedLLMClient.builder(model).budget(Budget.builder().tokens(300).build())
                .estimator(FixedEstimator.INSTANCE).build();
        client.chat(LLMRequest.builder().addUserMessage("hi").maxTokens(1000).build());
        assertThat(model.requests().get(0).getMaxTokens()).isEqualTo(200);
    }

    @Test
    void v2_3_refusedWhenLessThanAUsefulAnswerIsAffordable() {
        ScriptedLLMClient model = new ScriptedLLMClient();
        var client = BudgetedLLMClient.builder(model).budget(Budget.builder().tokens(160).build())
                .estimator(FixedEstimator.INSTANCE).build();
        assertThatThrownBy(() -> client.chat(Standard.request())).isInstanceOf(BudgetExceeded.class);
        assertThat(model.calls()).isZero();
    }

    @Test
    void v2_3b_aSmallRequestedAnswerStillFits() {
        ScriptedLLMClient model = new ScriptedLLMClient();
        var client = Standard.client(model, Budget.builder().tokens(150).build());
        client.chat(Standard.request());
        assertThat(model.requests().get(0).getMaxTokens()).isEqualTo(50);
    }

    @Test
    void v2_4_reportedUsageReplacesTheReservation() {
        Budget b = Budget.builder().tokens(1000).build();
        var client = Standard.client(new ScriptedLLMClient(120, 30), b);
        LLMResponse r = client.chat(Standard.request());
        assertThat(b.spent().tokens()).isEqualTo(150);
        assertThat(b.spent().promptTokens()).isEqualTo(120);
        assertThat(b.spent().estimated()).isFalse();
        assertThat(r.getMetadata()).doesNotContainKey(BudgetedLLMClient.ESTIMATED);
        // nothing left reserved: 5 more standard calls fit exactly into the remaining 850 − 100 margin
        for (int i = 0; i < 5; i++) client.chat(Standard.request());
        assertThat(b.spent().tokens()).isEqualTo(900);
    }

    @Test
    void v2_5_missingUsageIsEstimatedAndFlagged() {
        Budget b = Budget.builder().tokens(1000).build();
        LLMResponse r = BudgetedLLMClient.builder(new NoUsageClient()).budget(b)
                .estimator(FixedEstimator.INSTANCE).build().chat(Standard.request());
        assertThat(b.spent().tokens()).isEqualTo(210);
        assertThat(b.spent().estimated()).isTrue();
        assertThat(r.getMetadata()).containsEntry(BudgetedLLMClient.ESTIMATED, true);
        assertThat(r.getTokenUsage().getTotalTokens()).isEqualTo(210);
    }

    @Test
    void v2_6_aFailedCallIsChargedItsPrompt() {
        Budget b = Budget.builder().tokens(1000).build();
        FailingClient model = new FailingClient();
        var client = BudgetedLLMClient.builder(model).budget(b).estimator(FixedEstimator.INSTANCE).build();
        assertThatThrownBy(() -> client.chat(Standard.request()))
                .isInstanceOf(LLMException.class).hasMessage("provider down");
        assertThat(b.spent().tokens()).isEqualTo(100);
        assertThat(b.spent().calls()).isEqualTo(1);
        assertThat(b.spent().estimated()).isTrue();
    }

    @Test
    void v2_8_aProviderThatIgnoresTheCapIsChargedInFullAndStopsTheNextCall() {
        Budget b = Budget.builder().tokens(500).build();
        GreedyClient model = new GreedyClient();
        var client = Standard.client(model, b);
        client.chat(Standard.request());
        assertThat(b.spent().tokens()).isEqualTo(600);
        assertThat(b.spent().overdraw()).isEqualTo(100);
        assertThatThrownBy(() -> client.chat(Standard.request())).isInstanceOf(BudgetExceeded.class);
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void v3_1_aCostBudgetWithoutPricesIsRejectedBeforeTheCall() {
        ScriptedLLMClient model = new ScriptedLLMClient();
        var client = BudgetedLLMClient.builder(model).budget(Budget.builder().cost("0.50").build()).build();
        assertThatThrownBy(() -> client.chat(Standard.request()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("price table");
        assertThat(model.calls()).isZero();
    }

    @Test
    void v3_1b_aCostBudgetForAnUnpricedModelNamesTheModel() {
        var client = BudgetedLLMClient.builder(new ScriptedLLMClient()).budget(Budget.builder().cost("0.50").build())
                .prices(Standard.PRICES).build();
        assertThatThrownBy(() -> client.chat(LLMRequest.builder().model("gemini/pro").addUserMessage("x").build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("gemini/pro");
    }

    @Test
    void v3_5_costBudgetInExactMoney() {
        Budget b = Budget.builder().cost("0.001").build();
        ScriptedLLMClient model = new ScriptedLLMClient();
        var client = Standard.client(model, b);
        for (int i = 0; i < 5; i++) client.chat(Standard.request());
        assertThat(b.spent().cost()).isEqualByComparingTo(new BigDecimal("0.0010"));
        assertThatThrownBy(() -> client.chat(Standard.request()))
                .isInstanceOfSatisfying(BudgetExceeded.class, e -> assertThat(e.dimension()).isEqualTo(Dimension.COST));
        assertThat(model.calls()).isEqualTo(5);
    }

    @Test
    void costIsReportedPerResponseWhenPriced() {
        LLMResponse r = Standard.client(new ScriptedLLMClient(), Budget.unlimited("run")).chat(Standard.request());
        assertThat((BigDecimal) r.getMetadata().get(BudgetedLLMClient.COST)).isEqualByComparingTo("0.0002");
    }

    @Test
    void noLimitsMeansNoCapIsAdded() {
        ScriptedLLMClient model = new ScriptedLLMClient();
        BudgetedLLMClient.builder(model).budget(Budget.unlimited("run")).build().chat(Standard.request());
        assertThat(model.requests().get(0).getMaxTokens()).isNull();
    }

    @Test
    void streamingIsSettledWhenTheStreamEnds() {
        Budget b = Budget.builder().tokens(1000).build();
        var client = Standard.client(new ScriptedLLMClient(), b);
        String text = client.chatStream(Standard.request()).map(LLMResponse::getContent).collect(Collectors.joining());
        assertThat(text).isEqualTo("ok");
        assertThat(b.spent().tokens()).isEqualTo(150);
        assertThat(b.spent().calls()).isEqualTo(1);
    }

    @Test
    void chargeListenersSeeEverySettledCall() {
        var client = Standard.client(new ScriptedLLMClient(), Budget.unlimited("run"));
        List<Charge> charges = new java.util.concurrent.CopyOnWriteArrayList<>();
        client.addChargeListener((model, charge) -> charges.add(charge));
        client.chat(Standard.request());
        client.chat(Standard.request());
        assertThat(charges).extracting(Charge::tokens).containsExactly(150L, 150L);
    }
}
