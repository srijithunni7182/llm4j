package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.http.HttpClientWrapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.Headers;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** R11, R13: the HTTP layer reports every attempt and every sleep to the active trace. */
class HttpCallTraceTest {

    private MockWebServer server;
    private final List<Duration> slept = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        while (CallTrace.active()) CallTrace.end();
        server.shutdown();
    }

    private HttpClientWrapper http(int maxRetries) {
        RetryPolicy policy =
                RetryPolicy.builder()
                        .maxRetries(maxRetries)
                        .addRetryableStatusCode(429)
                        .addRetryableStatusCode(500)
                        .sleeper(slept::add)
                        .build();
        return new HttpClientWrapper(Duration.ofSeconds(5), Duration.ofSeconds(5), policy, false);
    }

    private String post(HttpClientWrapper http) {
        return http.post(server.url("/v1/chat").toString(), "{}", Headers.of());
    }

    @Test
    void aFirstTrySuccessIsOneAttemptAndNoWait() {
        server.enqueue(new MockResponse().setBody("{}"));
        CallTrace.begin();
        post(http(3));
        CallTrace.Totals t = CallTrace.end();
        assertThat(t.attempts()).isEqualTo(1);
        assertThat(t.waitedMillis()).isZero();
    }

    @Test
    void retriesAfterServerErrorsCountEachAttemptAndEachSleep() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setBody("{}"));
        CallTrace.begin();
        post(http(3));
        CallTrace.Totals t = CallTrace.end();
        assertThat(t.attempts()).isEqualTo(3);
        assertThat(slept).hasSize(2);
        assertThat(t.waitedMillis()).isEqualTo(slept.stream().mapToLong(Duration::toMillis).sum());
    }

    @Test
    void aRateLimitWaitIsCountedAsWaiting() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "5"));
        server.enqueue(new MockResponse().setBody("{}"));
        CallTrace.begin();
        post(http(3));
        CallTrace.Totals t = CallTrace.end();
        assertThat(t.attempts()).isEqualTo(2);
        assertThat(t.waitedMillis()).isBetween(5000L, 5500L);
    }

    @Test
    void attemptsThatEndInFailureAreStillCounted() {
        for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(500));
        CallTrace.begin();
        assertThatThrownBy(() -> post(http(2))).isInstanceOf(LLMException.class);
        CallTrace.Totals t = CallTrace.end();
        assertThat(t.attempts()).isEqualTo(3);
        assertThat(slept).hasSize(2);
    }

    @Test
    void aNonRetryableFailureIsOneAttempt() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad"));
        CallTrace.begin();
        assertThatThrownBy(() -> post(http(3))).isInstanceOf(LLMException.class);
        assertThat(CallTrace.end().attempts()).isEqualTo(1);
    }

    @Test
    void withNoTraceTheBehaviourIsUnchanged() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        assertThat(post(http(3))).isEqualTo("{\"ok\":true}");
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(slept).hasSize(1);
        assertThat(CallTrace.active()).isFalse();
    }
}
