package io.github.llm4j.provider.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.ContentBlockedException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.exception.ServiceUnavailableException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.LLMResponse.FinishReason;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.ollama.OllamaProvider;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import java.time.Duration;
import java.util.List;
import okhttp3.Headers;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Edge responses the conformance suite's happy/sad paths don't reach. */
class ProviderEdgesTest {

    MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    LLMConfig config(String path, String model, boolean logging) {
        return LLMConfig.builder().apiKey("k").baseUrl(server.url(path).toString().replaceAll("/+$", "")).defaultModel(model)
                .enableLogging(logging)
                .retryPolicy(RetryPolicy.builder().maxRetries(1).initialBackoff(Duration.ofMillis(1))
                        .retryableStatusCodes(RetryPolicy.defaultPolicy().getRetryableStatusCodes()).build())
                .build();
    }

    static LLMRequest ask() {
        return LLMRequest.builder().addUserMessage("hi").build();
    }

    MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void silentServersAreRetriedThenReported() {
        LLMConfig quick = LLMConfig.builder().apiKey("k").enableLogging(true).timeout(Duration.ofMillis(300))
                .retryPolicy(RetryPolicy.builder().maxRetries(1).initialBackoff(Duration.ofMillis(1))
                        .retryableStatusCodes(RetryPolicy.defaultPolicy().getRetryableStatusCodes()).build())
                .build();
        HttpClientWrapper http = new HttpClientWrapper(quick);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setBody("fine"));
        assertThat(http.post(server.url("/x").toString(), "{}", new Headers.Builder().build())).isEqualTo("fine");

        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        assertThatThrownBy(() -> http.post(server.url("/x").toString(), "{}", new Headers.Builder().build()))
                .isExactlyInstanceOf(LLMException.class).hasMessageContaining("HTTP request failed");

        server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"));
        server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"));
        assertThatThrownBy(() -> http.post(server.url("/x").toString(), "{}", new Headers.Builder().build()))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("<html>bad gateway</html>");
    }

    @Test
    void geminiTruncationWithNoTextAndPromptBlocks() {
        GoogleProvider g = new GoogleProvider(config("/v1beta", "gemini-2.5-flash", false));
        server.enqueue(json("{\"candidates\":[{\"content\":{\"role\":\"model\"},\"finishReason\":\"MAX_TOKENS\"}]}"));
        LLMResponse r = g.chat(ask());
        assertThat(r.getFinishReason()).isEqualTo(FinishReason.LENGTH);
        assertThat(r.getContent()).contains("truncated");

        server.enqueue(json("{\"promptFeedback\":{\"blockReason\":\"PROHIBITED_CONTENT\"}}"));
        assertThatThrownBy(() -> g.chat(ask())).isInstanceOf(ContentBlockedException.class);

        server.enqueue(json("{\"candidates\":[]}"));
        assertThatThrownBy(() -> g.chat(ask())).isExactlyInstanceOf(ProviderException.class).hasMessageContaining("No candidates");

        server.enqueue(json("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[]},\"finishReason\":\"STOP\"}]}"));
        assertThatThrownBy(() -> g.chat(ask())).isExactlyInstanceOf(ProviderException.class).hasMessageContaining("No parts");

        server.enqueue(json("{\"error\":{\"code\":403,\"message\":\"denied\"}}"));
        assertThatThrownBy(() -> g.chat(ask())).isInstanceOf(AuthenticationException.class);
        server.enqueue(json("{\"error\":{\"code\":400,\"message\":\"bad\"}}"));
        assertThatThrownBy(() -> g.chat(ask())).isInstanceOf(InvalidRequestException.class);
        server.enqueue(json("{\"error\":{\"code\":418,\"message\":\"teapot\"}}"));
        assertThatThrownBy(() -> g.chat(ask())).isExactlyInstanceOf(ProviderException.class);
    }

    @ParameterizedTest
    @CsvSource({"401,AuthenticationException", "404,InvalidRequestException", "429,RateLimitException",
            "500,ServiceUnavailableException", "409,ProviderException"})
    void geminiStreamErrorsAreTyped(int code, String type) {
        GoogleProvider g = new GoogleProvider(config("/v1beta", "gemini-2.5-flash", false));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"error\":{\"code\":" + code + ",\"message\":\"m\",\"status\":\"S\"}}\n\n"));
        assertThatThrownBy(() -> g.chatStream(ask()).toList())
                .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo(type));
    }

    @Test
    void geminiStreamsStopOnSafetyAndRejectBadEvents() {
        GoogleProvider g = new GoogleProvider(config("/v1beta", "gemini-2.5-flash", false));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Sure\"}]}}]}\n\n"
                        + "data: {\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"SAFETY\"}]}\n\n"));
        assertThatThrownBy(() -> g.chatStream(ask()).toList()).isInstanceOf(ContentBlockedException.class);

        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {not json\n\n"));
        assertThatThrownBy(() -> g.chatStream(ask()).toList()).isExactlyInstanceOf(ProviderException.class)
                .hasMessageContaining("Unreadable stream event");

        GoogleProvider noModel = new GoogleProvider(config("/v1beta", null, false));
        assertThatThrownBy(() -> noModel.chatStream(ask())).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void ollamaStreamEdges() {
        OllamaProvider o = new OllamaProvider(config("/api", "llama3.2", false));
        server.enqueue(new MockResponse().setBody("{\"message\":{\"content\":\"hi\"},\"done\":false}\n{\"done\":true}\n"));
        List<LLMResponse> chunks = o.chatStream(ask()).toList();
        assertThat(chunks).extracting(LLMResponse::getContent).containsExactly("hi", "");
        assertThat(chunks.get(1).getFinishReason()).isEqualTo(FinishReason.STOP);
        assertThat(chunks.get(1).getTokenUsage()).isNull();

        server.enqueue(new MockResponse().setBody("not json\n"));
        assertThatThrownBy(() -> o.chatStream(ask()).toList()).hasMessageContaining("Unreadable stream line");

        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"error\":\"model 'x' not found\"}"));
        assertThatThrownBy(() -> o.chatStream(ask())).isInstanceOf(InvalidRequestException.class).hasMessageContaining("model 'x' not found");

        server.enqueue(new MockResponse().setBody("{\"error\":\"out of memory\"}"));
        assertThatThrownBy(() -> o.chat(ask())).isExactlyInstanceOf(ProviderException.class).hasMessageContaining("out of memory");
    }

    @Test
    void sarvamErrorBodiesInA200() {
        SarvamChatProvider s = new SarvamChatProvider(config("", "sarvam-m", false));
        server.enqueue(json("{\"error\":\"quota exhausted\"}"));
        assertThatThrownBy(() -> s.chat(ask())).isInstanceOf(ProviderException.class).hasMessageContaining("quota exhausted");
        server.enqueue(json("{\"choices\":[]}"));
        assertThatThrownBy(() -> s.chat(ask())).isInstanceOf(ProviderException.class);
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {oops\n\n"));
        assertThatThrownBy(() -> s.chatStream(ask()).toList()).hasMessageContaining("Unreadable stream event");
    }

    @Test
    void rateLimitsStillCarryThroughStreams() {
        OllamaProvider o = new OllamaProvider(config("/api", "llama3.2", false));
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "7200").setBody("{\"error\":\"slow down\"}"));
        assertThatThrownBy(() -> o.chatStream(ask())).isInstanceOf(RateLimitException.class);
    }
}
