package io.github.llm4j.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.fixtures.Fixtures;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.openai.Dialect;
import io.github.llm4j.provider.openai.OpenAiCompatibleProvider;
import io.github.llm4j.provider.openai.OpenAiResponsesProvider;
import io.github.llm4j.provider.openai.ResponsesDialect;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Model discovery on the providers that offer it: the path comes from the dialect, the answer is
 * read from what each vendor really sent (recorded 2026-10-22), and a vendor without such an
 * endpoint says so instead of returning an invented list.
 */
class ListModelsTest {

    private static final String CANARY = "sk-canary-listing-4f8a";

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

    private LLMConfig config() {
        return LLMConfig.builder()
                .apiKey(CANARY)
                .baseUrl(server.url("/v1").toString())
                .retryPolicy(RetryPolicy.builder().maxRetries(0).build())
                .build();
    }

    /** Anthropic's base URL is the host; the {@code /v1} is part of its paths. */
    private LLMConfig hostOnly() {
        return LLMConfig.builder()
                .apiKey(CANARY)
                .baseUrl(server.url("/").toString())
                .retryPolicy(RetryPolicy.builder().maxRetries(0).build())
                .build();
    }

    private void reply(String fixture) {
        server.enqueue(new MockResponse().setBody(Fixtures.load(fixture).body()));
    }

    @Test
    void openAiListsTheIdsFromTheRecordedAnswer() throws Exception {
        reply("openai/list-models.fixture");
        OpenAiCompatibleProvider p = OpenAiCompatibleProvider.openai(config());

        assertThat(p.listModels()).startsWith("gpt-3.5-turbo-0125").hasSize(4);

        RecordedRequest r = server.takeRequest();
        assertThat(r.getMethod()).isEqualTo("GET");
        assertThat(r.getPath()).isEqualTo("/v1/models");
        assertThat(r.getHeader("Authorization")).isEqualTo("Bearer " + CANARY);
    }

    @Test
    void xaiOnTheChatAndOnTheResponsesApiListTheSameWay() throws Exception {
        reply("xai/list-models.fixture");
        reply("xai/list-models.fixture");

        String[] chat = OpenAiCompatibleProvider.xai(config()).listModels();
        String[] responses = OpenAiResponsesProvider.xai(config()).listModels();

        assertThat(chat).isEqualTo(responses).contains("grok-4.20-0309-non-reasoning");
        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/models");
    }

    @Test
    void openAiResponsesProviderListsToo() throws Exception {
        reply("openai/list-models.fixture");

        assertThat(OpenAiResponsesProvider.openai(config()).listModels()).hasSize(4);
    }

    @Test
    void firstAvailableModelIsTheFirstListed() {
        reply("openai/list-models.fixture");

        assertThat(OpenAiCompatibleProvider.openai(config()).getFirstAvailableModel())
                .isEqualTo("gpt-3.5-turbo-0125");
    }

    @Test
    void geminiListsTheModelIdsFromTheRecordedAnswerWithoutThePrefix() throws Exception {
        reply("google/list-models.fixture");
        GoogleProvider p =
                new GoogleProvider(
                        LLMConfig.builder()
                                .apiKey(CANARY)
                                .baseUrl(server.url("/v1beta").toString())
                                .retryPolicy(RetryPolicy.builder().maxRetries(0).build())
                                .build());

        assertThat(p.listModels())
                .containsExactly(
                        "gemini-2.5-flash",
                        "gemini-2.5-pro",
                        "gemini-2.5-flash-preview-tts",
                        "gemini-2.5-pro-preview-tts");
        RecordedRequest r = server.takeRequest();
        assertThat(r.getPath()).startsWith("/v1beta/models");
        assertThat(r.getHeader("x-goog-api-key")).isEqualTo(CANARY);
    }

    @Test
    void anthropicAsksForOnePageAndSendsItsOwnHeaders() throws Exception {
        reply("anthropic/list-models.fixture");

        String[] ids = new AnthropicProvider(hostOnly()).listModels();

        assertThat(ids).startsWith("claude-haiku-5-5").hasSize(4);
        RecordedRequest r = server.takeRequest();
        assertThat(r.getPath()).isEqualTo("/v1/models?limit=1000");
        assertThat(r.getHeader("x-api-key")).isEqualTo(CANARY);
        assertThat(r.getHeader("anthropic-version")).isNotBlank();
        assertThat(r.getHeader("Authorization")).isNull();
    }

    @Test
    void anEmptyListGivesNoFirstModel() {
        server.enqueue(new MockResponse().setBody("{\"data\":[]}"));

        OpenAiCompatibleProvider p = OpenAiCompatibleProvider.openai(config());

        assertThat(p.getFirstAvailableModel()).isNull();
    }

    @Test
    void aVendorWithoutAnEndpointSaysSoAndMakesNoCall() {
        LLMConfig config = config();

        assertThatThrownBy(
                        () ->
                                OpenAiCompatibleProvider.of(Dialect.PERPLEXITY_ROUTER, config)
                                        .listModels())
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("no endpoint that lists");
        assertThatThrownBy(
                        () ->
                                new OpenAiResponsesProvider(ResponsesDialect.PERPLEXITY, config)
                                        .listModels())
                .isInstanceOf(InvalidRequestException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void aBodyWithoutADataArrayIsAnError() {
        server.enqueue(new MockResponse().setBody("{\"models\":[]}"));

        assertThatThrownBy(() -> OpenAiCompatibleProvider.openai(config()).listModels())
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("without a data array");
    }

    @Test
    void aRefusedKeyIsAnAuthenticationErrorThatDoesNotEchoTheKey() {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(401)
                        .setBody("{\"error\":{\"message\":\"Incorrect API key\"}}"));

        assertThatThrownBy(() -> new AnthropicProvider(hostOnly()).listModels())
                .isInstanceOf(AuthenticationException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(CANARY));
    }

    @Test
    void theGenericDialectUsesItsConfiguredBaseUrl() throws Exception {
        reply("openai/list-models.fixture");

        OpenAiCompatibleProvider.of(Dialect.GENERIC, config()).listModels();

        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/models");
    }
}
