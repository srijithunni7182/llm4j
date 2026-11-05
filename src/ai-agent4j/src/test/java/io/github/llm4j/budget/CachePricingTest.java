package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.budget.fixtures.FixedEstimator;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.Providers;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A cache read and a cache write cost their own rates (docs/specs/31-multimodal-input, R4): the
 * reads are cheaper than fresh input and the writes dearer, and a model with no cache price is
 * priced as before.
 */
class CachePricingTest {

    // per million tokens: input 3, output 15, search 0, cache read 0.30, cache write 3.75
    private static final PriceTable.Price CLAUDE =
            new PriceTable.Price(
                    new BigDecimal("3.00"),
                    new BigDecimal("15.00"),
                    BigDecimal.ZERO,
                    new BigDecimal("0.30"),
                    new BigDecimal("3.75"));

    @TempDir Path dir;

    @Test
    void pricesFreshReadAndWrittenInputAtTheirOwnRates() {
        // 300 fresh * 3 + 600 read * 0.30 + 100 written * 3.75 + 100 out * 15 = 2955 millionths
        assertThat(CLAUDE.cost(1000, 100, 600, 100)).isEqualByComparingTo("0.002955");
    }

    @Test
    void aPriceWithoutCacheRatesChargesCachedTokensAsOrdinaryInput() {
        PriceTable.Price plain =
                new PriceTable.Price(new BigDecimal("3.00"), new BigDecimal("15.00"));

        assertThat(plain.cost(1000, 100, 600, 100)).isEqualByComparingTo(plain.cost(1000, 100));
    }

    @Test
    void theOldTwoAndThreeArgumentConstructorsStillWork() {
        PriceTable.Price searching =
                new PriceTable.Price(BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("14"));

        assertThat(searching.searchPerThousand()).isEqualByComparingTo("14");
        assertThat(searching.cacheReadPerMillion()).isNull();
        assertThat(searching.cacheWritePerMillion()).isNull();
    }

    @Test
    void neverChargesMoreCachedTokensThanThePromptHolds() {
        // a provider reporting 900 read + 900 written of a 1000 token prompt cannot be believed
        assertThat(CLAUDE.cost(1000, 0, 900, 900))
                .isEqualByComparingTo(CLAUDE.cost(1000, 0, 900, 100));
    }

    @Test
    void loadsCacheRatesAfterTheSearchRate() throws Exception {
        Path file = dir.resolve("prices.properties");
        Files.writeString(
                file,
                "a/model = 3.00 / 15.00 / 0 / 0.30 / 3.75\n"
                        + "b/model = 3.00 / 15.00 / 0 / 0.30\n"
                        + "c/model = 3.00 / 15.00\n");

        PriceTable prices = PriceTable.load(file);

        assertThat(prices.price("a/model").orElseThrow().cacheReadPerMillion())
                .isEqualByComparingTo("0.30");
        assertThat(prices.price("a/model").orElseThrow().cacheWritePerMillion())
                .isEqualByComparingTo("3.75");
        assertThat(prices.price("b/model").orElseThrow().cacheWritePerMillion()).isNull();
        assertThat(prices.price("c/model").orElseThrow().cacheReadPerMillion()).isNull();
    }

    @Test
    void refusesALineWithTooManyFields() throws Exception {
        Path file = dir.resolve("prices.properties");
        Files.writeString(file, "a/model = 1 / 2 / 3 / 4 / 5 / 6\n");

        assertThatThrownBy(() -> PriceTable.load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cache");
    }

    /** Answers with 1000 prompt tokens, 600 read from the cache and 100 written to it. */
    private static final class CachingClient implements LLMClient {
        @Override
        public LLMResponse chat(LLMRequest request) {
            return LLMResponse.builder()
                    .content("ok")
                    .model("test/model")
                    .tokenUsage(1000, 100, 1100)
                    .addMetadata(Providers.CACHED_TOKENS, 600)
                    .addMetadata(Providers.CACHE_WRITE_TOKENS, 100)
                    .build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }
    }

    private static LLMResponse call(PriceTable.Price price) {
        return BudgetedLLMClient.builder(new CachingClient())
                .budget(Budget.builder().tokens(100_000).build())
                .estimator(FixedEstimator.INSTANCE)
                .prices(PriceTable.of(Map.of("test/model", price)))
                .build()
                .chat(LLMRequest.builder().model("test/model").addUserMessage("hi").build());
    }

    @Test
    void theBudgetedClientSettlesACallAtTheCacheRates() {
        assertThat((BigDecimal) call(CLAUDE).getMetadata().get(BudgetedLLMClient.COST))
                .isEqualByComparingTo("0.002955");
    }

    @Test
    void aModelWithoutCacheRatesIsPricedAsBefore() {
        PriceTable.Price plain =
                new PriceTable.Price(new BigDecimal("3.00"), new BigDecimal("15.00"));

        assertThat((BigDecimal) call(plain).getMetadata().get(BudgetedLLMClient.COST))
                .isEqualByComparingTo(plain.cost(1000, 100));
    }
}
