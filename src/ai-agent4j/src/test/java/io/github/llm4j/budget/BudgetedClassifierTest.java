package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.budget.fixtures.FixedEstimator;
import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.classify.ClassifyUsage;
import io.github.llm4j.classify.YesNoAnswer;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.RateLimitException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A classify call is charged to the budgets like a model call (R9): reserved first, refused when it
 * will not fit, settled with the reported usage.
 */
class BudgetedClassifierTest {

    private static final ClassifyRequest ASK =
            ClassifyRequest.builder().state("a short message").yesNo("q", "Is it urgent?").build();

    /** Answers yes, reports the given usage, and counts how often it was called. */
    private static final class Counting implements Classifier {
        final AtomicInteger calls = new AtomicInteger();
        final ClassifyUsage usage;

        Counting(ClassifyUsage usage) {
            this.usage = usage;
        }

        @Override
        public ClassifyResult classify(ClassifyRequest request) {
            calls.incrementAndGet();
            return new ClassifyResult(Map.of("q", new YesNoAnswer(0.9, true)), usage);
        }
    }

    private static Counting jev() {
        return new Counting(new ClassifyUsage(100, 10, 150, "jev", "jev-latest"));
    }

    private static BudgetedClassifier.Builder budgeted(Classifier c, Budget b) {
        return BudgetedClassifier.builder(c).budget(b).estimator(FixedEstimator.INSTANCE);
    }

    @Test
    void theCallThatWillNotFitNeverReachesTheClassifier() {
        Counting jev = jev();
        Budget b = Budget.builder().tokens(1000).build();
        Classifier metered = budgeted(jev, b).build();

        int made = 0;
        while (true) {
            try {
                metered.classify(ASK);
                made++;
            } catch (BudgetExceeded e) {
                break;
            }
        }

        assertThat(made).isGreaterThan(1);
        assertThat(jev.calls.get()).isEqualTo(made); // the refused call was not made
        assertThat(b.spent().tokens())
                .isEqualTo(made * 110L); // what was reported, not what was reserved
    }

    @Test
    void whatIsChargedIsWhatTheBackendReported() {
        Budget b = Budget.builder().tokens(100_000).calls(10).build();

        budgeted(jev(), b).build().classify(ASK);

        assertThat(b.spent().tokens()).isEqualTo(110);
        assertThat(b.spent().calls()).isEqualTo(1);
    }

    @Test
    void aBackendThatReportsNoUsageIsChargedAnEstimate() {
        Budget b = Budget.builder().tokens(100_000).build();
        List<Charge> seen = new ArrayList<>();

        BudgetedClassifier c =
                budgeted(new Counting(new ClassifyUsage(0, 0, 5, "x", "m")), b).build();
        c.addChargeListener((m, charge) -> seen.add(charge));
        c.classify(ASK);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).estimated()).isTrue();
        assertThat(seen.get(0).promptTokens()).isEqualTo(100); // the fixed estimator's prompt
    }

    @Test
    void aCallCountBudgetRefusesTheNextCall() {
        Counting jev = jev();
        Classifier metered = budgeted(jev, Budget.builder().calls(2).build()).build();

        metered.classify(ASK);
        metered.classify(ASK);

        assertThatThrownBy(() -> metered.classify(ASK)).isInstanceOf(BudgetExceeded.class);
        assertThat(jev.calls.get()).isEqualTo(2);
    }

    @Test
    void aFailedCallIsStillChargedItsPromptExceptARateLimit() {
        Budget b = Budget.builder().tokens(100_000).build();
        Classifier boom =
                request -> {
                    throw new LLMException("down");
                };
        Classifier limited =
                request -> {
                    throw new RateLimitException("slow down");
                };

        assertThatThrownBy(() -> budgeted(boom, b).build().classify(ASK))
                .isInstanceOf(LLMException.class);
        long afterFailure = b.spent().tokens();
        assertThatThrownBy(() -> budgeted(limited, b).build().classify(ASK))
                .isInstanceOf(RateLimitException.class);

        assertThat(afterFailure).isEqualTo(100); // a provider may bill a failed request
        assertThat(b.spent().tokens()).isEqualTo(100); // a 429 is not billed
        assertThat(b.spent().calls()).isEqualTo(2);
    }

    @Test
    void jevsInputOnlyPriceIsAppliedFromThePriceTable() {
        PriceTable prices =
                PriceTable.of(
                        Map.of(
                                "jev-latest",
                                new PriceTable.Price(new BigDecimal("0.042"), BigDecimal.ZERO)));
        Budget b = Budget.builder().cost(new BigDecimal("1.00")).build();
        List<Charge> seen = new ArrayList<>();

        BudgetedClassifier c = budgeted(jev(), b).prices(prices).model("jev-latest").build();
        c.addChargeListener((m, charge) -> seen.add(charge));
        c.classify(ASK);

        assertThat(seen.get(0).cost())
                .isEqualByComparingTo(
                        new BigDecimal(
                                "0.0000042")); // 100 input tokens at $0.042 per million; output is
        // free
    }

    @Test
    void aCostBudgetNeedsAPriceForTheModelAndSaysWhich() {
        Budget b = Budget.builder().cost(new BigDecimal("1.00")).build();

        assertThatThrownBy(() -> budgeted(jev(), b).build().classify(ASK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("price table");
        assertThatThrownBy(
                        () ->
                                budgeted(jev(), b)
                                        .prices(PriceTable.of(Map.of()))
                                        .model("jev-latest")
                                        .build()
                                        .classify(ASK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jev-latest");
    }

    @Test
    void theListenerSeesTheModelAndAFailingListenerChangesNothing() {
        List<String> models = new ArrayList<>();
        BudgetedClassifier c =
                budgeted(jev(), Budget.builder().tokens(100_000).build())
                        .model("jev-latest")
                        .build();
        c.addChargeListener((m, charge) -> models.add(m));
        c.addChargeListener(
                (m, charge) -> {
                    throw new IllegalStateException("boom");
                });

        ClassifyResult r = c.classify(ASK);

        assertThat(models).containsExactly("jev-latest");
        assertThat(r.yesNo("q").probability()).isEqualTo(0.9);
    }

    @Test
    void withNoBudgetsTheCallPassesStraightThrough() {
        Counting jev = jev();

        ClassifyResult r = BudgetedClassifier.builder(jev).build().classify(ASK);

        assertThat(r.usage().inputTokens()).isEqualTo(100);
        assertThat(jev.calls.get()).isEqualTo(1);
    }
}
