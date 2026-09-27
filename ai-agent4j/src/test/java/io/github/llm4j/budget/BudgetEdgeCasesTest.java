package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.budget.fixtures.FailingClient;
import io.github.llm4j.budget.fixtures.FixedEstimator;
import io.github.llm4j.budget.fixtures.ScriptedLLMClient;
import io.github.llm4j.budget.fixtures.Standard;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The less-travelled paths: formatting, other dimensions, listeners, releases and streaming errors. */
class BudgetEdgeCasesTest {

    @Test
    void limitsDescribeThemselves() {
        assertThat(Limits.NONE).hasToString("unlimited");
        assertThat(Limits.NONE.any()).isFalse();
        assertThat(new Limits(10L, null, null)).hasToString("tokens 10");
        assertThat(new Limits(null, 3L, null)).hasToString("calls 3");
        assertThat(new Limits(null, null, new BigDecimal("0.5"))).hasToString("cost $0.5");
        assertThat(new Limits(10L, 3L, new BigDecimal("0.5"))).hasToString("tokens 10, calls 3, cost $0.5");
        assertThat(new Limits(null, 3L, null).constrainsOutput()).isFalse();
        assertThat(new Limits(null, null, BigDecimal.ONE).constrainsOutput()).isTrue();
    }

    @Test
    void spentAddsUp() {
        Spent a = Spent.of(new Charge(10, 5, 1, new BigDecimal("0.1"), false));
        Spent b = Spent.of(new Charge(1, 1, 1, null, true));
        Spent sum = a.plus(b);
        assertThat(sum.tokens()).isEqualTo(17);
        assertThat(sum.calls()).isEqualTo(2);
        assertThat(sum.cost()).isEqualByComparingTo("0.1");
        assertThat(sum.estimated()).isTrue();
        assertThat(new Charge(1, 2, 1, null, false).cost()).isEqualByComparingTo("0");
    }

    @Test
    void exhaustionIsVisibleOnEveryDimension() {
        Budget calls = Budget.builder().calls(1).build();
        Standard.client(new ScriptedLLMClient(), calls).chat(Standard.request());
        assertThat(calls.exhausted()).isTrue();

        Budget cost = Budget.builder().cost("0.0002").build();
        Standard.client(new ScriptedLLMClient(), cost).chat(Standard.request());
        assertThat(cost.exhausted()).isTrue();
        assertThat(cost.remaining().cost()).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0"));

        Budget tokens = Budget.builder().tokens(150).build();
        Standard.client(new ScriptedLLMClient(), tokens).chat(Standard.request());
        assertThat(tokens.exhausted()).isTrue();
        assertThat(Budget.unlimited("x").exhausted()).isFalse();
    }

    @Test
    void warningsFireOnCallsCostAndRestore() {
        List<BudgetEvent> events = new ArrayList<>();
        Budget calls = Budget.builder().name("c").calls(2).warnAt(0.5).listener(events::add).build();
        Standard.client(new ScriptedLLMClient(), calls).chat(Standard.request());
        Budget cost = Budget.builder().name("m").cost("0.0004").warnAt(0.5).listener(events::add).build();
        Standard.client(new ScriptedLLMClient(), cost).chat(Standard.request());
        Budget restored = Budget.builder().name("r").tokens(100).listener(events::add).build();
        restored.restore(new Spent(90, 0, 1, BigDecimal.ZERO, false, 0));
        assertThat(events).extracting(BudgetEvent::budget).containsExactly("c", "m", "r");
        assertThat(events).allSatisfy(e -> assertThat(e.kind()).isEqualTo(BudgetEvent.Kind.WARNING));
    }

    @Test
    void aBrokenListenerNeverBreaksMetering() {
        Budget b = Budget.builder().tokens(150).warnAt(0.5).build();
        BudgetListener broken = e -> { throw new IllegalStateException("boom"); };
        b.addListener(broken);
        var client = Standard.client(new ScriptedLLMClient(), b);
        client.chat(Standard.request());
        assertThatThrownBy(() -> client.chat(Standard.request())).isInstanceOf(BudgetExceeded.class);
        b.removeListener(broken);
        assertThat(b.toString()).contains("tokens 150");
    }

    @Test
    void builderValidatesWarnAt() {
        assertThatThrownBy(() -> Budget.builder().warnAt(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Budget.builder().warnAt(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Budget.builder().warnAt(1).build().warnAt()).isEqualTo(1.0);
    }

    @Test
    void refusalsExplainThemselves() {
        Budget calls = Budget.builder().name("run").calls(1).build();
        var client = Standard.client(new ScriptedLLMClient(), calls);
        client.chat(Standard.request());
        assertThatThrownBy(() -> client.chat(Standard.request())).hasMessage("budget exhausted: run (calls 1/1)");

        Budget cost = Budget.builder().name("run").cost("0.0002").build();
        var priced = Standard.client(new ScriptedLLMClient(), cost);
        priced.chat(Standard.request());
        assertThatThrownBy(() -> priced.chat(Standard.request()))
                .isInstanceOfSatisfying(BudgetExceeded.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("budget exhausted: run (cost $0.0002/$0.0002)");
                    assertThat(e.spent().calls()).isEqualTo(1);
                    assertThat(e.limits().cost()).isEqualByComparingTo("0.0002");
                });
    }

    @Test
    void aReleasedLeaseReturnsItsReservation() {
        Budget b = Budget.builder().tokens(200).build();
        BudgetSet set = BudgetSet.of(b);
        BudgetSet.Lease lease = set.reserve(100, 50L, null);
        assertThatThrownBy(() -> set.reserve(100, 50L, null)).isInstanceOf(BudgetExceeded.class);
        lease.release();
        lease.release(); // idempotent
        assertThat(set.reserve(100, 50L, null).output()).isEqualTo(50L);
        assertThat(b.spent().tokens()).isZero();
    }

    @Test
    void setsIgnoreNullsAndDuplicates() {
        Budget a = Budget.unlimited("a");
        BudgetSet set = BudgetSet.of(a, null, a).with(a, null);
        assertThat(set.members()).containsExactly(a);
        assertThat(BudgetSet.of().isEmpty()).isTrue();
        assertThat(BudgetSet.of(List.of(a)).members()).containsExactly(a);
        assertThat(BudgetSet.of().reserve(100, null, null).output()).isNull();
    }

    @Test
    void freeOutputIsLimitedOnlyByTokens() {
        BudgetSet.Pricing freeOutput = new PriceTable.Price(new BigDecimal("1.00"), BigDecimal.ZERO);
        BudgetSet set = BudgetSet.of(Budget.builder().cost("1").build());
        assertThat(set.reserve(100, 70L, freeOutput).output()).isEqualTo(70L);
        assertThat(set.reserve(100, null, freeOutput).output()).isEqualTo(BudgetSet.DEFAULT_OUTPUT);
    }

    @Test
    void theEstimatorCountsEveryMessage() {
        LLMRequest r = LLMRequest.builder().addMessage(Message.system("x".repeat(40)))
                .addMessage(Message.user("y".repeat(40))).build();
        assertThat(CharsPerTokenEstimator.INSTANCE.prompt(r)).isEqualTo(22);
        assertThat(CharsPerTokenEstimator.INSTANCE.completion(null)).isZero();
        assertThat(CharsPerTokenEstimator.INSTANCE.completion("abcd")).isEqualTo(2);
    }

    @Test
    void anExistingMaxTokensThatAlreadyFitsIsKept() {
        ScriptedLLMClient model = new ScriptedLLMClient();
        var client = BudgetedLLMClient.builder(model).budget(Budget.builder().tokens(1000).build())
                .estimator(FixedEstimator.INSTANCE).perCallCap(80).build();
        LLMRequest sent = LLMRequest.builder().addUserMessage("x").maxTokens(80)
                .additionalParameters(Map.of("k", "v")).build();
        client.chat(sent);
        assertThat(model.requests().get(0)).isSameAs(sent);
        client.chat(LLMRequest.builder().addUserMessage("x").maxTokens(60)
                .additionalParameters(Map.of("k", "v")).build());
        assertThat(model.requests().get(1).getMaxTokens()).isEqualTo(60);
        assertThat(client.delegate()).isSameAs(model);
    }

    @Test
    void perCallCapMustBePositive() {
        assertThatThrownBy(() -> BudgetedLLMClient.builder(new ScriptedLLMClient()).perCallCap(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamingFailuresAreCharged() {
        Budget b = Budget.builder().tokens(1000).build();
        var failing = BudgetedLLMClient.builder(new FailingClient()).budget(b).estimator(FixedEstimator.INSTANCE).build();
        assertThatThrownBy(() -> failing.chatStream(Standard.request())).hasMessage("provider down");
        assertThat(b.spent().tokens()).isEqualTo(100);

        LLMClient breaksMidStream = new ScriptedLLMClient() {
            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.<LLMResponse>generate(() -> { throw new IllegalStateException("cut"); }).limit(1);
            }
        };
        Budget c = Budget.builder().tokens(1000).build();
        var metered = BudgetedLLMClient.builder(breaksMidStream).budget(c).estimator(FixedEstimator.INSTANCE).build();
        assertThatThrownBy(() -> metered.chatStream(Standard.request()).forEach(x -> { })).hasMessage("cut");
        assertThat(c.spent().tokens()).isEqualTo(100);
        assertThat(c.spent().estimated()).isTrue();
    }

    @Test
    void anAbandonedStreamIsSettledOnClose() {
        Budget b = Budget.builder().tokens(1000).build();
        var client = BudgetedLLMClient.builder(new ScriptedLLMClient()).budget(b).estimator(FixedEstimator.INSTANCE).build();
        try (Stream<LLMResponse> s = client.chatStream(Standard.request())) {
            assertThat(s.iterator().hasNext()).isTrue();
        }
        assertThat(b.spent().calls()).isEqualTo(1);
    }

    @Test
    void aListenerThatThrowsNeverBreaksTheCall() {
        var client = Standard.client(new ScriptedLLMClient(), Budget.unlimited("run"));
        client.addChargeListener((m, c) -> { throw new IllegalStateException("boom"); });
        assertThat(client.chat(Standard.request()).getContent()).isEqualTo("ok");
    }
}
