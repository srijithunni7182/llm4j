package io.github.llm4j.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.ollama.OllamaProvider;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.Headers;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verification plan V2.1–V2.6. */
class HttpRateLimitTest {

    private MockWebServer server;
    private final MutableClock clock = new MutableClock();
    private final List<Duration> slept = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private RetryPolicy policy(int maxRetries) {
        return RetryPolicy.builder().maxRetries(maxRetries)
                .addRetryableStatusCode(429).addRetryableStatusCode(500)
                .clock(clock).sleeper(slept::add).build();
    }

    private HttpClientWrapper http(int maxRetries) {
        return new HttpClientWrapper(Duration.ofSeconds(5), Duration.ofSeconds(5), policy(maxRetries), false);
    }

    private String url() {
        return server.url("/v1/chat").toString();
    }

    @Test
    void v2_1_shortResetIsWaitedOutInline() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "5"));
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        String body = http(3).post(url(), "{}", Headers.of());
        assertThat(body).isEqualTo("{\"ok\":true}");
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(slept).hasSize(1);
        assertThat(slept.get(0)).isBetween(Duration.ofSeconds(5), Duration.ofMillis(5500));
    }

    @Test
    void v2_2_longResetIsRaisedWithoutRetrying() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "3600").setBody("slow down"));
        assertThatThrownBy(() -> http(3).post(url(), "{}", Headers.of()))
                .isInstanceOfSatisfying(RateLimitException.class, e -> {
                    assertThat(e.info().resetAt()).isEqualTo(Instant.parse("2026-09-27T11:00:00Z"));
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(3600L);
                    assertThat(e.getStatusCode()).isEqualTo(429);
                    assertThat(e.getMessage()).contains("2026-09-27T11:00:00Z", "slow down");
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(slept).isEmpty();
    }

    @Test
    void v2_3_retriesRunOutThenRaise() {
        for (int i = 0; i < 4; i++) server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "5"));
        assertThatThrownBy(() -> http(3).post(url(), "{}", Headers.of())).isInstanceOf(RateLimitException.class);
        assertThat(server.getRequestCount()).isEqualTo(4);
        assertThat(slept).hasSize(3).allSatisfy(d -> assertThat(d).isBetween(Duration.ofSeconds(5), Duration.ofMillis(5500)));
    }

    @Test
    void v2_3b_noRetryPolicyStillReportsTheReset() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "2"));
        HttpClientWrapper http = new HttpClientWrapper(Duration.ofSeconds(5), Duration.ofSeconds(5),
                RetryPolicy.builder().maxRetries(0).clock(clock).sleeper(slept::add).build(), false);
        assertThatThrownBy(() -> http.post(url(), "{}", Headers.of()))
                .isInstanceOfSatisfying(RateLimitException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(2L));
    }

    @Test
    void v2_4_otherStatusesKeepExponentialBackoff() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setBody("ok"));
        assertThat(http(3).post(url(), "{}", Headers.of())).isEqualTo("ok");
        assertThat(slept).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
    }

    @Test
    void v2_4b_serverErrorAfterRetriesIsAPlainLlmException() {
        server.enqueue(new MockResponse().setResponseCode(503));
        assertThatThrownBy(() -> http(0).post(url(), "{}", Headers.of()))
                .isInstanceOf(LLMException.class).isNotInstanceOf(RateLimitException.class);
    }

    @Test
    void v2_5_googleProviderPassesTheDailyQuotaThrough() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody(RateLimitParsersTest.GEMINI_PER_DAY));
        LLMConfig config = LLMConfig.builder().apiKey("test-key").baseUrl(server.url("/v1beta").toString())
                .defaultModel("gemini-2.5-flash").retryPolicy(policy(3)).build();
        GoogleProvider google = new GoogleProvider(config);
        LLMRequest request = LLMRequest.builder().addUserMessage("hi").build();
        assertThatThrownBy(() -> google.chat(request))
                .isNotInstanceOf(ProviderException.class)
                .isInstanceOfSatisfying(RateLimitException.class, e -> {
                    assertThat(e.info().scope()).isEqualTo(RateLimitInfo.Scope.DAILY_QUOTA);
                    assertThat(e.info().resetAt()).isEqualTo(Instant.parse("2026-09-28T07:00:00Z"));
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void v2_6_ollamaAndSarvamPassRateLimitsThrough() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "600"));
        LLMConfig ollamaConfig = LLMConfig.builder().baseUrl(server.url("/api").toString())
                .defaultModel("llama3").retryPolicy(policy(3)).build();
        LLMRequest request = LLMRequest.builder().addUserMessage("hi").build();
        assertThatThrownBy(() -> new OllamaProvider(ollamaConfig).chat(request)).isInstanceOf(RateLimitException.class);

        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "600"));
        LLMConfig sarvamConfig = LLMConfig.builder().apiKey("k").baseUrl(server.url("").toString().replaceAll("/$", ""))
                .defaultModel("sarvam-m").retryPolicy(policy(3)).build();
        assertThatThrownBy(() -> new SarvamChatProvider(sarvamConfig).chat(request)).isInstanceOf(RateLimitException.class);
    }

    @Test
    void successClearsTheFallbackBackoff() {
        server.enqueue(new MockResponse().setResponseCode(429)); // no reset info: 60 s estimate
        server.enqueue(new MockResponse().setBody("ok"));
        server.enqueue(new MockResponse().setResponseCode(429));
        HttpClientWrapper http = new HttpClientWrapper(Duration.ofSeconds(5), Duration.ofSeconds(5),
                RetryPolicy.builder().maxRetries(0).clock(clock).sleeper(slept::add).build(), false);
        assertThatThrownBy(() -> http.post(url(), "{}", Headers.of()))
                .isInstanceOfSatisfying(RateLimitException.class, e -> {
                    assertThat(e.info().estimated()).isTrue();
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(60L);
                });
        assertThat(http.post(url(), "{}", Headers.of())).isEqualTo("ok");
        assertThatThrownBy(() -> http.post(url(), "{}", Headers.of()))
                .isInstanceOfSatisfying(RateLimitException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(60L));
    }

    @Test
    void legacyConstructorsStillWork() {
        RateLimitException legacy = new RateLimitException("slow", 30L);
        assertThat(legacy.info()).isNull();
        assertThat(legacy.getRetryAfterSeconds()).isEqualTo(30L);
        RateLimitInfo est = legacy.infoOrEstimate(MutableClock.T0, Duration.ofSeconds(60));
        assertThat(est.resetAt()).isEqualTo(MutableClock.T0.plusSeconds(30));
        RateLimitInfo fallback = new RateLimitException("slow").infoOrEstimate(MutableClock.T0, Duration.ofSeconds(60));
        assertThat(fallback.estimated()).isTrue();
        assertThat(fallback.resetAt()).isEqualTo(MutableClock.T0.plusSeconds(60));
    }
}
