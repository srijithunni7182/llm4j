package io.github.llm4j.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.ServiceUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import okhttp3.Headers;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The shared streaming reader and typed HTTP failures. */
class StreamingBodyTest {

    MockWebServer server;
    HttpClientWrapper http;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        http = new HttpClientWrapper(LLMConfig.builder().apiKey("k").retryPolicy(RetryPolicy.builder()
                .maxRetries(1).initialBackoff(Duration.ofMillis(1)).addRetryableStatusCode(529).build()).build());
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    StreamingBody open(String body) {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body));
        return http.stream(server.url("/s").toString(), "{}", new Headers.Builder().build());
    }

    @Test
    void serverSentEventsAreParsedPerTheFormat() {
        List<StreamingBody.SseEvent> events = open("""
                : a comment
                event: first
                data: {"a":1}

                data: line one
                data: line two

                event:second
                data:no-space

                data: last without a trailing blank line""").events().toList();
        assertThat(events).containsExactly(
                new StreamingBody.SseEvent("first", "{\"a\":1}"),
                new StreamingBody.SseEvent(null, "line one\nline two"),
                new StreamingBody.SseEvent("second", "no-space"),
                new StreamingBody.SseEvent(null, "last without a trailing blank line"));
    }

    @Test
    void linesSkipBlanksAndABodyReadsOnce() {
        StreamingBody body = open("{\"n\":1}\n\n{\"n\":2}\n");
        var lines = body.lines().iterator();
        assertThat(lines.next()).isEqualTo("{\"n\":1}");
        assertThat(lines.next()).isEqualTo("{\"n\":2}");
        assertThat(lines.hasNext()).isFalse();
        assertThat(lines.hasNext()).isFalse(); // asking again after the end is fine
        assertThatThrownBy(body::events).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failuresBeforeTheStreamAreTypedAndRetried() {
        server.enqueue(new MockResponse().setResponseCode(529)
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"));
        server.enqueue(new MockResponse().setBody("data: ok\n\n"));
        assertThat(http.stream(server.url("/s").toString(), "{}", new Headers.Builder().build()).events().toList())
                .extracting(StreamingBody.SseEvent::data).containsExactly("ok");

        server.enqueue(new MockResponse().setResponseCode(403).setHeader("request-id", "req_9")
                .setBody("{\"error\":{\"message\":\"forbidden\"}}"));
        assertThatThrownBy(() -> http.stream(server.url("/s").toString(), "{}", new Headers.Builder().build()))
                .isExactlyInstanceOf(AuthenticationException.class)
                .hasMessageContaining("403").hasMessageContaining("forbidden").hasMessageContaining("request-id req_9")
                .satisfies(e -> assertThat(((LLMException) e).getResponseBody()).contains("forbidden"));
    }

    @Test
    void errorBodiesAreDescribedFromTheirUsualShapes() {
        assertThat(HttpClientWrapper.describe("{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}"))
                .isEqualTo("invalid_request_error: bad");
        assertThat(HttpClientWrapper.describe("{\"error\":{\"code\":400,\"message\":\"x\",\"status\":\"INVALID_ARGUMENT\"}}"))
                .isEqualTo("INVALID_ARGUMENT: x");
        assertThat(HttpClientWrapper.describe("[{\"error\":{\"message\":\"listed\"}}]")).isEqualTo("listed");
        assertThat(HttpClientWrapper.describe("{\"error\":\"model not found\"}")).isEqualTo("model not found");
        assertThat(HttpClientWrapper.describe("{\"message\":\"plain\"}")).isEqualTo("plain");
        assertThat(HttpClientWrapper.describe("<html>oops</html>")).isEqualTo("<html>oops</html>");
        assertThat(HttpClientWrapper.describe("")).isEqualTo("(no body)");
        assertThat(HttpClientWrapper.describe("x".repeat(600))).hasSize(501);
        assertThat(HttpClientWrapper.failure("p", 422, "{}", Map.of())).isInstanceOf(InvalidRequestException.class);
        // as sent by the real Anthropic API on a 401: the id is in the body, not a header
        assertThat(HttpClientWrapper.failure("anthropic", 401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"},\"request_id\":\"req_011Cf\"}",
                Map.of()).getMessage()).isEqualTo("HTTP request failed with status 401: authentication_error: invalid x-api-key (request-id req_011Cf)");
        assertThat(HttpClientWrapper.failure("p", 529, "{}", Map.of())).isInstanceOf(ServiceUnavailableException.class);
        assertThat(HttpClientWrapper.failure("p", 418, "{}", Map.of())).isExactlyInstanceOf(LLMException.class)
                .satisfies(e -> assertThat(((LLMException) e).getStatusCode()).isEqualTo(418));
    }
}
