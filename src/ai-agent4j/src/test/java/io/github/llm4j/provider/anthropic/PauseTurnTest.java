package io.github.llm4j.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.Providers;
import io.github.llm4j.provider.fixtures.Fixtures;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A turn Claude pauses ({@code stop_reason: pause_turn}) is continued by sending it back as the
 * last assistant message, and the caller sees one answer (preview: documentation-derived fixtures).
 */
class PauseTurnTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MockWebServer server;
    private AnthropicProvider provider;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        provider =
                new AnthropicProvider(
                        LLMConfig.builder()
                                .apiKey("sk-ant-canary-pause")
                                .baseUrl(server.url("/").toString())
                                .retryPolicy(RetryPolicy.builder().maxRetries(0).build())
                                .build());
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private static LLMRequest ask() {
        return LLMRequest.builder()
                .model("claude-haiku-5-5")
                .addUserMessage("Which city hosted the most recent Olympic Games?")
                .maxTokens(200)
                .build();
    }

    private void enqueue(String fixture) {
        server.enqueue(new MockResponse().setBody(Fixtures.load(fixture).body()));
    }

    @Test
    void aPausedTurnIsContinuedAndTheRoundsAreOneAnswer() throws Exception {
        enqueue("anthropic/pause-turn-1.fixture");
        enqueue("anthropic/pause-turn-2.fixture");

        LLMResponse r = provider.chat(ask());

        assertThat(r.getContent()).isEqualTo("Let me check. Paris.");
        assertThat(r.getMetadata()).containsEntry(Providers.FINISH_REASON_RAW, "end_turn");
        assertThat(r.getTokenUsage().getPromptTokens()).isEqualTo(250);
        assertThat(r.getTokenUsage().getCompletionTokens()).isEqualTo(30);
        assertThat(server.getRequestCount()).isEqualTo(2);

        server.takeRequest();
        JsonNode second = JSON.readTree(server.takeRequest().getBody().readUtf8());
        JsonNode messages = second.path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(1).path("content").get(1).path("type").asText())
                .isEqualTo("server_tool_use");
    }

    @Test
    void anAnswerThatWasNotPausedMakesOneCall() {
        enqueue("anthropic/pause-turn-2.fixture");

        assertThat(provider.chat(ask()).getContent()).isEqualTo("Paris.");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void aTurnThatKeepsPausingStopsAtTheLimitAndSaysSo() {
        for (int i = 0; i <= AnthropicProvider.MAX_CONTINUATIONS + 2; i++) {
            enqueue("anthropic/pause-turn-1.fixture");
        }

        LLMResponse r = provider.chat(ask());

        assertThat(server.getRequestCount()).isEqualTo(1 + AnthropicProvider.MAX_CONTINUATIONS);
        assertThat(r.getMetadata()).containsEntry(Providers.FINISH_REASON_RAW, "pause_turn");
    }
}
