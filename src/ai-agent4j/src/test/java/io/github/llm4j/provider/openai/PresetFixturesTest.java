package io.github.llm4j.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.fixtures.Fixtures;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Replays each preset's fixtures through its provider. Kimi, Qwen and the Orchestrator fixtures are
 * documentation-derived (preview).
 */
class PresetFixturesTest {

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private OpenAiCompatibleProvider provider(Dialect dialect, String model) {
        return new OpenAiCompatibleProvider(
                dialect,
                LLMConfig.builder()
                        .apiKey("k-123456")
                        .baseUrl(server.url("/v1").toString())
                        .defaultModel(model)
                        .build());
    }

    private void enqueue(Fixtures f, String type) {
        server.enqueue(new MockResponse().setHeader("Content-Type", type).setBody(f.body()));
    }

    @Test
    void kimiPlainAnswer() {
        enqueue(Fixtures.load("kimi/chat-plain.fixture"), "application/json");
        LLMResponse r =
                provider(Dialect.KIMI, "kimi-k2.6")
                        .chat(LLMRequest.builder().addUserMessage("hi").build());
        assertThat(r.getContent()).startsWith("Hello, Li Lei!");
        assertThat(r.getTokenUsage().getTotalTokens()).isEqualTo(40);
        assertThat(r.getMetadata()).containsEntry("usage.cached_tokens", 10);
    }

    @Test
    void kimiThinkingToolTurnKeepsItsReasoningForTheNextTurn() {
        enqueue(Fixtures.load("kimi/chat-thinking-tool.fixture"), "application/json");
        LLMResponse r =
                provider(Dialect.KIMI, "kimi-k3")
                        .chat(LLMRequest.builder().addUserMessage("weather?").build());
        assertThat(r.getToolCalls())
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.name()).isEqualTo("get_weather");
                            assertThat(c.arguments()).containsEntry("city", "Pune");
                        });
        assertThat(r.getProviderData())
                .containsValue("I need the weather, so I will call the tool.");
    }

    @Test
    void kimiStream() {
        enqueue(Fixtures.load("kimi/chat-stream.fixture"), "text/event-stream");
        List<LLMResponse> chunks =
                provider(Dialect.KIMI, "kimi-k2.6")
                        .chatStream(LLMRequest.builder().addUserMessage("hi").build())
                        .toList();
        assertThat(chunks.stream().map(LLMResponse::getContent).reduce("", String::concat))
                .isEqualTo("Hello there");
        assertThat(chunks.get(chunks.size() - 1).getTokenUsage().getTotalTokens()).isEqualTo(32);
    }

    @Test
    void qwenPlainAnswerAndThinkingStream() {
        enqueue(Fixtures.load("qwen/chat-plain.fixture"), "application/json");
        assertThat(
                        provider(Dialect.QWEN, "qwen3.8-max")
                                .chat(LLMRequest.builder().addUserMessage("hi").build())
                                .getContent())
                .isEqualTo("I am Qwen, a large language model.");

        enqueue(Fixtures.load("qwen/chat-thinking-stream.fixture"), "text/event-stream");
        List<LLMResponse> chunks =
                provider(Dialect.QWEN, "qwen3.8-max")
                        .chatStream(
                                LLMRequest.builder()
                                        .addUserMessage("9.9 or 9.11?")
                                        .reasoning(true)
                                        .build())
                        .toList();
        assertThat(
                        chunks.stream()
                                .map(LLMResponse::getReasoning)
                                .filter(x -> x != null)
                                .reduce("", String::concat))
                .isEqualTo("9.9 is 9.90, which is larger than 9.11.");
        assertThat(chunks.stream().map(LLMResponse::getContent).reduce("", String::concat))
                .isEqualTo("9.9 is larger.");
        assertThat(chunks.get(chunks.size() - 1).getTokenUsage().getTotalTokens()).isEqualTo(84);
    }

    @Test
    void orchestratorPlainAnswer() {
        enqueue(Fixtures.load("perplexity-router/chat-plain.fixture"), "application/json");
        assertThat(
                        provider(Dialect.PERPLEXITY_ROUTER, "perplexity/kimi-k3")
                                .chat(LLMRequest.builder().addUserMessage("hi").build())
                                .getContent())
                .isEqualTo("Paris.");
    }
}
