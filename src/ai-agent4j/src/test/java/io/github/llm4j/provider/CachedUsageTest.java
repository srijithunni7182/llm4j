package io.github.llm4j.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import java.io.IOException;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every provider reports cached input under one name (docs/specs/31-multimodal-input, R3): {@code
 * usage.cached_tokens} for a read from the cache, and for Anthropic, which charges for it, {@code
 * usage.cache_write_tokens} for a write. Prompt tokens keep meaning "including cached".
 */
class CachedUsageTest {

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

    private static final LLMRequest ASK = LLMRequest.builder().addUserMessage("hi").build();

    private AnthropicProvider anthropic() {
        return new AnthropicProvider(
                LLMConfig.builder()
                        .apiKey("test-key")
                        .baseUrl(server.url("/").toString())
                        .defaultModel("claude-sonnet-5-5")
                        .retryPolicy(RetryPolicy.builder().maxRetries(0).build())
                        .build());
    }

    private GoogleProvider google() {
        return new GoogleProvider(
                LLMConfig.builder()
                        .apiKey("test-key")
                        .baseUrl(server.url("/v1beta").toString().replaceAll("/$", ""))
                        .defaultModel("gemini-2.5-flash")
                        .build());
    }

    private void reply(String body) {
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
    }

    @Test
    void anthropicReportsWhatItReadFromAndWroteToTheCache() {
        reply(
                """
                {"id":"m1","model":"claude-sonnet-5-5","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"ok"}],
                 "usage":{"input_tokens":10,"cache_creation_input_tokens":300,
                          "cache_read_input_tokens":2000,"output_tokens":5}}""");

        LLMResponse response = anthropic().chat(ASK);

        assertThat(response.getMetadata())
                .containsEntry("usage.cached_tokens", 2000)
                .containsEntry("usage.cache_write_tokens", 300);
        assertThat(response.getTokenUsage().getPromptTokens()).isEqualTo(2310);
    }

    @Test
    void anthropicSaysNothingWhenTheCacheWasNotInvolved() {
        reply(
                """
                {"id":"m1","model":"claude-sonnet-5-5","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"ok"}],
                 "usage":{"input_tokens":10,"output_tokens":5}}""");

        assertThat(anthropic().chat(ASK).getMetadata())
                .doesNotContainKeys("usage.cached_tokens", "usage.cache_write_tokens");
    }

    @Test
    void anthropicReportsTheCacheOnTheLastChunkOfAStream() {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(
                                """
                                event: message_start
                                data: {"type":"message_start","message":{"model":"claude-sonnet-5-5","usage":{"input_tokens":10,"cache_creation_input_tokens":0,"cache_read_input_tokens":1500,"output_tokens":1}}}

                                event: content_block_start
                                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                                event: content_block_delta
                                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

                                event: message_delta
                                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

                                event: message_stop
                                data: {"type":"message_stop"}

                                """));

        List<LLMResponse> chunks = anthropic().chatStream(ASK).toList();

        assertThat(chunks.get(chunks.size() - 1).getMetadata())
                .containsEntry("usage.cached_tokens", 1500);
    }

    @Test
    void googleReportsTheCachedPartOfThePrompt() {
        reply(
                """
                {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":3000,"cachedContentTokenCount":2500,
                                  "candidatesTokenCount":4,"totalTokenCount":3004}}""");

        LLMResponse response = google().chat(ASK);

        assertThat(response.getMetadata()).containsEntry("usage.cached_tokens", 2500);
        assertThat(response.getTokenUsage().getPromptTokens()).isEqualTo(3000);
    }

    @Test
    void googleSaysNothingWithoutACachedCount() {
        reply(
                """
                {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":4,"totalTokenCount":7}}""");

        assertThat(google().chat(ASK).getMetadata()).doesNotContainKey("usage.cached_tokens");
    }
}
