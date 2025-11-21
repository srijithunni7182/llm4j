package io.github.llm4j.provider.openai;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

class OpenAIProviderTest {

    private MockWebServer mockServer;
    private OpenAIProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        LLMConfig config = LLMConfig.builder()
                .apiKey("test-api-key")
                .baseUrl(mockServer.url("/v1").toString())
                .retryPolicy(RetryPolicy.noRetry())
                .build();

        provider = new OpenAIProvider(config);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void testSuccessfulChatRequest() throws InterruptedException {
        String responseJson = """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652288,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Hello! How can I help you today?"
                        },
                        "finish_reason": "stop"
                    }],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 9,
                        "total_tokens": 19
                    }
                }
                """;

        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Hello")
                .model("gpt-4")
                .build();

        LLMResponse response = provider.chat(request);

        assertThat(response.getContent()).isEqualTo("Hello! How can I help you today?");
        assertThat(response.getModel()).isEqualTo("gpt-4");
        assertThat(response.getFinishReason()).isEqualTo(LLMResponse.FinishReason.STOP);
        assertThat(response.getTokenUsage()).isNotNull();
        assertThat(response.getTokenUsage().getPromptTokens()).isEqualTo(10);
        assertThat(response.getTokenUsage().getCompletionTokens()).isEqualTo(9);
        assertThat(response.getTokenUsage().getTotalTokens()).isEqualTo(19);

        RecordedRequest recordedRequest = mockServer.takeRequest();
        assertThat(recordedRequest.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recordedRequest.getHeader("Authorization")).isEqualTo("Bearer test-api-key");
        assertThat(recordedRequest.getHeader("Content-Type")).contains("application/json");
    }

    @Test
    void testMissingApiKeyThrows() {
        LLMConfig config = LLMConfig.builder()
                .baseUrl("https://api.openai.com/v1")
                .build();

        assertThatThrownBy(() -> new OpenAIProvider(config))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("API key");
    }

    @Test
    void testProviderName() {
        assertThat(provider.getProviderName()).isEqualTo("openai");
    }
}
