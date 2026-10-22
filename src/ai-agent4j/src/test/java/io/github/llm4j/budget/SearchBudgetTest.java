package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.grounding.Citation;
import io.github.llm4j.grounding.Grounding;
import io.github.llm4j.grounding.SearchUsage;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * R11, A13 and §4.5: searches are a budget dimension next to tokens, calls and cost; a breach halts
 * as for the others.
 */
class SearchBudgetTest {

    /** Answers every call with the given search usage, and remembers what it was sent. */
    private static final class Grounded implements LLMClient {
        final List<LLMRequest> seen = new ArrayList<>();
        SearchUsage usage;

        Grounded(SearchUsage usage) {
            this.usage = usage;
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            seen.add(request);
            LLMResponse.Builder b =
                    LLMResponse.builder()
                            .content("answer")
                            .model("test/model")
                            .tokenUsage(100, 50, 150)
                            .citations(
                                    List.of(
                                            new Citation(
                                                    1,
                                                    "https://a.example",
                                                    null,
                                                    null,
                                                    List.of())));
            if (usage != null && request.getGrounding() != null) b.searches(usage);
            return b.build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            LLMResponse whole = chat(request);
            return Stream.of(
                    LLMResponse.builder().content(whole.getContent()).build(),
                    LLMResponse.builder()
                            .content("")
                            .tokenUsage(100, 50, 150)
                            .searches(whole.getSearches())
                            .citations(whole.getCitations())
                            .build());
        }
    }

    private static LLMRequest grounded() {
        return LLMRequest.builder()
                .model("test/model")
                .addUserMessage("news?")
                .grounding(Grounding.webSearch().build())
                .build();
    }

    private static LLMRequest plain() {
        return LLMRequest.builder().model("test/model").addUserMessage("hi").build();
    }

    private static BudgetedLLMClient over(LLMClient model, Budget budget) {
        return BudgetedLLMClient.builder(model)
                .budget(budget)
                .estimator(io.github.llm4j.budget.fixtures.FixedEstimator.INSTANCE)
                .perCallCap(50)
                .build();
    }

    @Test
    void searchesAreCountedFromWhatTheVendorReported() {
        Budget b = Budget.builder().searches(5).build();
        BudgetedLLMClient client = over(new Grounded(SearchUsage.of(2)), b);
        client.chat(grounded());
        assertThat(b.spent().searches()).isEqualTo(2);
        assertThat(b.remaining().searches()).hasValue(3);
        assertThat(b.limits().searches()).isEqualTo(5);
        assertThat(b.limits().any()).isTrue();
        assertThat(b.limits().toString()).contains("searches 5");
    }

    @Test
    void whenTheSearchesAreUsedUpAGroundedCallIsRefusedBeforeItIsMadeButAPlainOneStillRuns() {
        Budget b = Budget.builder().searches(2).build();
        Grounded model = new Grounded(SearchUsage.of(1));
        BudgetedLLMClient client = over(model, b);
        client.chat(grounded());
        client.chat(grounded());
        assertThatThrownBy(() -> client.chat(grounded()))
                .isInstanceOfSatisfying(
                        BudgetExceeded.class,
                        e -> assertThat(e.dimension()).isEqualTo(Dimension.SEARCHES));
        assertThat(model.seen).hasSize(2);
        client.chat(plain());
        assertThat(model.seen).hasSize(3);
        assertThat(b.spent().searches()).isEqualTo(2);
    }

    @Test
    void anUnknownCountIsChargedTheRequestedCapAndMarkedEstimated() {
        Budget b = Budget.builder().searches(10).build();
        Grounded model = new Grounded(new SearchUsage(null, List.of(), null));
        BudgetedLLMClient client = over(model, b);
        client.chat(
                LLMRequest.builder()
                        .model("test/model")
                        .addUserMessage("x")
                        .grounding(Grounding.webSearch().maxSearches(3).build())
                        .build());
        assertThat(b.spent().searches()).isEqualTo(3);
        assertThat(b.spent().estimated()).isTrue();
        client.chat(grounded());
        assertThat(b.spent().searches()).isEqualTo(4); // no cap given: one
    }

    @Test
    void aVendorThatIgnoresTheCapIsCountedAndTheOverrunIsVisible() {
        Budget b = Budget.builder().searches(2).build();
        BudgetedLLMClient client = over(new Grounded(SearchUsage.of(5)), b);
        client.chat(grounded());
        assertThat(b.spent().searches()).isEqualTo(5);
        assertThat(b.spent().searchOverdraw()).isEqualTo(3);
        assertThat(b.remaining().searches()).hasValue(0);
        assertThat(b.exhausted()).isTrue();
        assertThatThrownBy(() -> client.chat(grounded())).isInstanceOf(BudgetExceeded.class);
    }

    @Test
    void aBudgetWithoutASearchLimitOnlyCounts() {
        Budget b = Budget.builder().tokens(100_000).build();
        BudgetedLLMClient client = over(new Grounded(SearchUsage.of(4)), b);
        client.chat(grounded());
        client.chat(grounded());
        assertThat(b.spent().searches()).isEqualTo(8);
        assertThat(b.remaining().searches()).isEmpty();
    }

    @Test
    void aStreamIsChargedFromItsFinalChunk() {
        Budget b = Budget.builder().searches(3).build();
        BudgetedLLMClient client = over(new Grounded(SearchUsage.of(2)), b);
        try (Stream<LLMResponse> s = client.chatStream(grounded())) {
            s.forEach(c -> {});
        }
        assertThat(b.spent().searches()).isEqualTo(2);
    }

    @Test
    void theResponseKeepsItsCitationsAndSearchesWhenMetered() {
        Budget b = Budget.builder().searches(3).build();
        LLMResponse r = over(new Grounded(SearchUsage.of(1)), b).chat(grounded());
        assertThat(r.getCitations()).hasSize(1);
        assertThat(r.getSearches().count()).isEqualTo(1);
    }

    @Test
    void searchesAreChargedPerThousandOnTopOfTokens() {
        PriceTable prices =
                PriceTable.of(
                        Map.of(
                                "test/model",
                                new PriceTable.Price(
                                        new BigDecimal("1.00"),
                                        new BigDecimal("2.00"),
                                        new BigDecimal("14.00"))));
        Budget b = Budget.builder().cost("1.00").build();
        BudgetedLLMClient client =
                BudgetedLLMClient.builder(new Grounded(SearchUsage.of(2)))
                        .budget(b)
                        .prices(prices)
                        .estimator(io.github.llm4j.budget.fixtures.FixedEstimator.INSTANCE)
                        .perCallCap(50)
                        .build();
        LLMResponse r = client.chat(grounded());
        // tokens: 100 * $1/M + 50 * $2/M = $0.0002; searches: 2 * $14 / 1000 = $0.028
        assertThat(b.spent().cost()).isEqualByComparingTo("0.0282");
        assertThat((BigDecimal) r.getMetadata().get(BudgetedLLMClient.COST))
                .isEqualByComparingTo("0.0282");
    }

    @Test
    void thePriceTableReadsAnOptionalPricePerThousandSearches(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path f = dir.resolve("prices.properties");
        java.nio.file.Files.writeString(
                f,
                "# model = input / output [/ searches per 1000]\ngemini/gemini-3.7-flash = 0.75 / 3.75 / 14.00\ngpt-6-luna = 0.10 / 0.50\n");
        PriceTable t = PriceTable.load(f);
        assertThat(t.price("gemini/gemini-3.7-flash").orElseThrow().searchCost(2))
                .isEqualByComparingTo("0.028");
        assertThat(t.price("gpt-6-luna").orElseThrow().searchCost(2)).isEqualByComparingTo("0");
        assertThat(t.price("gemini/gemini-3.7-flash").orElseThrow().searchPerThousand())
                .isEqualByComparingTo("14.00");
        assertThatThrownBy(
                        () -> {
                            java.nio.file.Files.writeString(f, "m = 1 / 2 / 3 / 4\n");
                            PriceTable.load(f);
                        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected");
    }

    @Test
    void aWindowedSearchBudgetRefillsWithItsWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-22T10:00:00Z"));
        Budget b = Budget.builder().searches(1).window(Window.DAY).clock(clock).build();
        BudgetedLLMClient client = over(new Grounded(SearchUsage.of(1)), b);
        client.chat(grounded());
        assertThatThrownBy(() -> client.chat(grounded())).isInstanceOf(BudgetExceeded.class);
        clock.advanceDays(1);
        client.chat(grounded());
        assertThat(b.spent().searches()).isEqualTo(1);
        assertThat(b.lifetimeSpent().searches()).isEqualTo(2);
    }

    @Test
    void spentRestoredFromAJournalKeepsItsSearches() {
        Budget b = Budget.builder().searches(10).build();
        b.restore(new Spent(10, 5, 1, BigDecimal.ZERO, false, 0, 4));
        assertThat(b.spent().searches()).isEqualTo(4);
        assertThat(Spent.NONE.plus(new Spent(1, 1, 1, BigDecimal.ZERO, false, 0, 2)).searches())
                .isEqualTo(2);
    }

    @Test
    void theOldShapesStillWork() {
        assertThat(new Limits(1L, 2L, null).searches()).isNull();
        assertThat(new Spent(1, 2, 3, BigDecimal.ZERO, false, 0).searches()).isZero();
        assertThat(new Charge(1, 2, 3, BigDecimal.ONE, false).searches()).isZero();
        assertThat(
                        new Remaining(
                                        java.util.OptionalLong.empty(),
                                        java.util.OptionalLong.empty(),
                                        java.util.Optional.empty())
                                .searches())
                .isEmpty();
    }

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceDays(int days) {
            now = now.plusSeconds(days * 86_400L);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
