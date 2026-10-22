package io.github.llm4j.provider.perplexity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.classify.ClassificationException;
import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.ClassifierContract;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.classify.Scale;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 8k, A20: {@link PerplexityDecisionsProvider} against a server that replays what the real API sent
 * (recorded 2026-10-22), plus the {@link ClassifierContract} every backend meets.
 */
class PerplexityDecisionsProviderTest {

    private static final String CANARY = "pplx-canary-7f3a9c1e5b2d";
    private static final ObjectMapper JSON = new ObjectMapper();

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    static String fixture(String name) {
        try (InputStream in =
                PerplexityDecisionsProviderTest.class.getResourceAsStream(
                        "/perplexity-decisions/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private LLMConfig.Builder config() {
        return LLMConfig.builder()
                .apiKey(CANARY)
                .baseUrl(server.url("/").toString().replaceAll("/$", ""))
                .retryPolicy(RetryPolicy.builder().maxRetries(0).build());
    }

    private static MockResponse recorded(String name) {
        return new MockResponse()
                .setResponseCode(Integer.parseInt(fixture(name + ".status").trim()))
                .setBody(fixture(name + ".response.json"))
                .setHeader("Content-Type", "application/json");
    }

    /** The request the recorded call answered: the review and its three questions. */
    static ClassifyRequest review() {
        return ClassifyRequest.builder()
                .state("title", "Battery died after two weeks")
                .state(
                        "review",
                        "The headphones sound great, but the battery stopped charging after two weeks.")
                .yesNo("defect", "Does the review report a product defect?")
                .choice(
                        "sentiment",
                        "What is the overall sentiment of the review?",
                        "positive",
                        "mixed",
                        "negative")
                .score(
                        "severity",
                        "How severe is the reported problem?",
                        Scale.of("Cosmetic", "Inconvenient", "Product unusable"))
                .build();
    }

    @Test
    void postsToTheDecisionsEndpointWithABearerKeyAndNoOtherAuthHeader() throws Exception {
        server.enqueue(recorded("ok"));
        Classifier.using(
                        new PerplexityDecisionsProvider(
                                config().defaultModel("pplx-decider-flash-latest").build()))
                .classify(review());

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getMethod()).isEqualTo("POST");
        assertThat(sent.getPath()).isEqualTo("/v1/decisions");
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + CANARY);
        assertThat(sent.getHeader("x-api-key")).isNull();
        JsonNode body = JSON.readTree(sent.getBody().readUtf8());
        assertThat(body.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("model", "state", "questions");
        assertThat(body.path("model").asText()).isEqualTo("pplx-decider-flash-latest");
        assertThat(body.path("questions").path("defect").path("type").asText()).isEqualTo("noul");
        // an option with no description is null, the documented way to let its name speak
        JsonNode criteria = body.path("questions").path("sentiment").path("criteria");
        assertThat(criteria.has("positive")).isTrue();
        assertThat(criteria.get("positive").isNull()).isTrue();
        assertThat(body.path("questions").path("severity").path("criteria")).hasSize(3);
    }

    @Test
    void theModelDefaultsToTheLatestAlias() throws Exception {
        server.enqueue(recorded("ok"));
        Classifier.using(new PerplexityDecisionsProvider(config().build())).classify(review());
        assertThat(JSON.readTree(server.takeRequest().getBody().readUtf8()).path("model").asText())
                .isEqualTo("pplx-decider-latest");
    }

    @Test
    void theRecordedReplyBecomesCalibratedTypedAnswers() {
        server.enqueue(recorded("ok"));
        ClassifyResult r =
                Classifier.using(new PerplexityDecisionsProvider(config().build()))
                        .classify(review());

        assertThat(r.yesNo("defect").probability()).isBetween(0.94, 0.95);
        assertThat(r.yesNo("defect").yes()).isTrue();
        assertThat(r.choice("sentiment").label()).isEqualTo("mixed");
        assertThat(r.choice("sentiment").confidence().getAsDouble()).isBetween(0.71, 0.72);
        assertThat(r.choice("sentiment").probabilities())
                .containsKeys("positive", "mixed", "negative");
        // a score is the probability-weighted level, here between "Inconvenient" and "Product
        // unusable"
        assertThat(r.score("severity").position()).isBetween(0.72, 0.73);
        assertThat(r.score("severity").nearest()).isEqualTo("Inconvenient");
        assertThat(r.score("severity").probabilities())
                .containsKeys("Cosmetic", "Inconvenient", "Product unusable");
        assertThat(r.choice("sentiment").calibrated()).isTrue();
        assertThat(r.usage().backend()).isEqualTo("perplexity_decisions");
        assertThat(r.usage().inputTokens()).isEqualTo(354);
        assertThat(r.usage().outputTokens()).isEqualTo(3);
        assertThat(r.usage().model()).isEqualTo("pplx-decider-flash-latest");
    }

    @Test
    void aBadRequestKeepsTheReasonAndNothingElse() {
        server.enqueue(recorded("bad_model"));
        assertThatThrownBy(
                        () ->
                                Classifier.using(
                                                new PerplexityDecisionsProvider(
                                                        config().defaultModel("pplx-decidr-latest")
                                                                .build()))
                                        .classify(review()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Perplexity Decisions rejected the request")
                .hasMessageContaining("Invalid model 'pplx-decidr-latest'")
                .satisfies(
                        e -> assertThat(((InvalidRequestException) e).getResponseBody()).isEmpty());
    }

    @Test
    void aMissingKeyIsRefusedAndTheKeyIsNeverInAnError() {
        assertThatThrownBy(() -> new PerplexityDecisionsProvider(LLMConfig.builder().build()))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("PERPLEXITY_API_KEY");
        server.enqueue(
                new MockResponse().setResponseCode(200).setBody("{\"model\":\"m\",\"usage\":{}}"));
        assertThatThrownBy(
                        () ->
                                Classifier.using(new PerplexityDecisionsProvider(config().build()))
                                        .classify(
                                                ClassifyRequest.builder()
                                                        .state("x")
                                                        .yesNo("defect", "?")
                                                        .build()))
                .isInstanceOf(ClassificationException.class)
                .satisfies(
                        e ->
                                assertThat(e.getMessage())
                                        .doesNotContain(CANARY)
                                        .contains("Perplexity Decisions"));
    }
}
