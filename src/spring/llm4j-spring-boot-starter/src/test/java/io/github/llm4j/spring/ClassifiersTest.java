package io.github.llm4j.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.SecretStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * Phase 8k and 8e in the starter: decision-model backends from {@code llm4j.classifiers.*}, a
 * search budget and a price per thousand searches.
 */
class ClassifiersTest {

    private static final String REPLY =
            "{\"model\":\"pplx-decider-flash-latest\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.9}},"
                    + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}";

    private static SecretStore keys() {
        InMemorySecretStore store = new InMemorySecretStore();
        store.put("PPLX", "pplx-secret-key");
        store.put("JEV", "jev-secret-key");
        return store;
    }

    @Test
    void aPerplexityDecisionsBackendAnswersThroughTheConfiguredAddress() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(REPLY));
            String base = server.url("/").toString().replaceAll("/$", "");
            Fixtures.runner()
                    .withBean(SecretStore.class, ClassifiersTest::keys)
                    .withPropertyValues(
                            "llm4j.classifiers.decide.provider=perplexity-decisions",
                            "llm4j.classifiers.decide.model=pplx-decider-flash-latest",
                            "llm4j.classifiers.decide.api-key-secret=PPLX",
                            "llm4j.classifiers.decide.base-url=" + base,
                            "llm4j.classifiers.jev.provider=jev",
                            "llm4j.classifiers.jev.api-key-secret=JEV")
                    .run(
                            ctx -> {
                                assertThat(ctx).hasNotFailed();
                                Classifiers all = ctx.getBean(Classifiers.class);
                                assertThat(all.names()).containsExactly("decide", "jev");
                                Classifier decide = all.get("decide");
                                var r =
                                        decide.classify(
                                                ClassifyRequest.builder()
                                                        .state("x")
                                                        .yesNo("q", "yes?")
                                                        .build());
                                assertThat(r.yesNo("q").probability()).isEqualTo(0.9);
                                assertThat(r.usage().backend()).isEqualTo("perplexity_decisions");
                                try {
                                    var sent = server.takeRequest();
                                    assertThat(sent.getPath()).isEqualTo("/v1/decisions");
                                    assertThat(sent.getHeader("Authorization"))
                                            .isEqualTo("Bearer pplx-secret-key");
                                } catch (InterruptedException e) {
                                    throw new IllegalStateException(e);
                                }
                                assertThat(all.get("jev")).isNotNull();
                            });
        }
    }

    @Test
    void aMissingKeyOrAnUnknownProviderIsRefusedNamingTheProperty() {
        Fixtures.runner()
                .withPropertyValues("llm4j.classifiers.d.provider=perplexity_decisions")
                .run(
                        ctx ->
                                assertThat(ctx.getStartupFailure())
                                        .hasStackTraceContaining("llm4j.classifiers.d")
                                        .hasStackTraceContaining("no API key"));
        Fixtures.runner()
                .withBean(SecretStore.class, ClassifiersTest::keys)
                .withPropertyValues(
                        "llm4j.classifiers.d.provider=gemini",
                        "llm4j.classifiers.d.api-key-secret=PPLX")
                .run(
                        ctx ->
                                assertThat(ctx.getStartupFailure())
                                        .hasStackTraceContaining(
                                                "llm4j.classifiers.d.provider=gemini is not known"));
    }

    @Test
    void theKeyNeverShowsInTheSettingsText() {
        var d =
                new Llm4jProperties.Decider(
                        "jev", null, "literal-key-123", null, null, null, null, null);
        assertThat(d.toString()).doesNotContain("literal-key-123");
    }

    @Test
    void aSearchBudgetAndAPricePerThousandSearchesAreRead() {
        Fixtures.runner()
                .withPropertyValues(
                        "llm4j.budget.searches=7",
                        "llm4j.pricing.models.m.input-per-million=1",
                        "llm4j.pricing.models.m.output-per-million=2",
                        "llm4j.pricing.models.m.search-per-thousand=14")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx.getBean(Budget.class).limits().searches()).isEqualTo(7L);
                            assertThat(
                                            ctx.getBean(PriceTable.class)
                                                    .price("m")
                                                    .orElseThrow()
                                                    .searchCost(2))
                                    .isEqualByComparingTo("0.028");
                        });
    }
}
