package io.github.llm4j.loom.generic.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.generic.support.RecordingEffects;
import io.github.llm4j.loom.generic.support.StubResolver;
import io.github.llm4j.loom.tools.generic.HttpSupport;
import io.github.llm4j.loom.tools.generic.NetPolicy;
import io.github.llm4j.loom.tools.generic.ToolRefusal;
import io.github.llm4j.loom.tools.generic.UnknownOutcomeException;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class HttpSupportTest {

    MockWebServer server;
    final RecordingEffects ctx = new RecordingEffects();

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    HttpSupport http(boolean follow, int retries, long maxBytes, Duration timeout) {
        NetPolicy.Rules rules = new NetPolicy.Rules(true, false, Set.of(), follow);
        return new HttpSupport(new NetPolicy(rules, NetPolicy.Resolver.SYSTEM, "localhost"), ctx, timeout, maxBytes, retries);
    }

    Request get(String path) {
        return new Request.Builder().url(server.url(path)).build();
    }

    @Test
    @Tag("V3.7")
    void aHugeResponseIsReadOnlyUpToTheCapAndSaysSo() {
        server.enqueue(new MockResponse().setBody("x".repeat(10 * 1024 * 1024)));

        HttpSupport.Reply reply = http(false, 0, 64 * 1024, Duration.ofSeconds(10)).send(get("/big"), HttpSupport.Retry.NONE);

        assertThat(reply.body()).hasSize(64 * 1024);
        assertThat(reply.truncated()).isTrue();
    }

    @Test
    @Tag("V3.3")
    void theConnectionUsesTheCheckedAddressWithoutASecondLookup() throws Exception {
        // First answer: loopback (where the server is). A second lookup would return a private address.
        StubResolver dns = new StubResolver().answer("pinned.test", "127.0.0.1").answer("pinned.test", "10.9.9.9");
        NetPolicy.Rules rules = new NetPolicy.Rules(true, true, Set.of(), false);
        HttpSupport http = new HttpSupport(new NetPolicy(rules, dns, "pinned.test"), ctx, Duration.ofSeconds(5), 4096, 0);
        server.enqueue(new MockResponse().setBody("hello"));

        HttpSupport.Reply reply = http.send(new Request.Builder().url("http://pinned.test:" + server.getPort() + "/x").build(), HttpSupport.Retry.NONE);

        assertThat(reply.bodyText()).isEqualTo("hello");
        assertThat(dns.lookups("pinned.test")).as("resolved once, then connected to that answer").isEqualTo(1);
    }

    @Test
    @Tag("V3.2")
    void aPrivateAddressIsRefusedBeforeAnyConnection() {
        StubResolver dns = new StubResolver().answer("internal.test", "10.0.0.5");
        HttpSupport http = new HttpSupport(new NetPolicy(new NetPolicy.Rules(true, false, Set.of(), false), dns, null), ctx,
                Duration.ofSeconds(5), 4096, 0);

        assertThatThrownBy(() -> http.send(new Request.Builder().url("http://internal.test:" + server.getPort() + "/").build(), HttpSupport.Retry.NONE))
                .isInstanceOf(ToolRefusal.class).hasMessageContaining("private");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @Tag("V3.5")
    void redirectsAreNotFollowedByDefault() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "http://169.254.169.254/latest/meta-data"));

        HttpSupport.Reply reply = http(false, 0, 4096, Duration.ofSeconds(5)).send(get("/r"), HttpSupport.Retry.NONE);

        assertThat(reply.status()).isEqualTo(302);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @Tag("V3.5")
    void aFollowedRedirectToAPrivateAddressIsRefused() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "http://169.254.169.254/latest/meta-data"));
        StubResolver dns = new StubResolver().answer("localhost", "127.0.0.1").answer("169.254.169.254", "169.254.169.254");
        HttpSupport http = new HttpSupport(new NetPolicy(new NetPolicy.Rules(true, false, Set.of(), true), dns, "localhost"), ctx,
                Duration.ofSeconds(5), 4096, 0);

        assertThatThrownBy(() -> http.send(get("/r"), HttpSupport.Retry.NONE)).isInstanceOf(ToolRefusal.class).hasMessageContaining("link-local");
    }

    @Test
    @Tag("V3.5")
    void aFollowedRedirectToAnAllowedHostWorksAndTheChainIsBounded() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/second"));
        server.enqueue(new MockResponse().setBody("arrived"));
        assertThat(http(true, 0, 4096, Duration.ofSeconds(5)).send(get("/first"), HttpSupport.Retry.NONE).bodyText()).isEqualTo("arrived");

        for (int i = 0; i < 5; i++) server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/loop"));
        assertThatThrownBy(() -> http(true, 0, 4096, Duration.ofSeconds(5)).send(get("/loop"), HttpSupport.Retry.NONE))
                .isInstanceOf(ToolRefusal.class).hasMessageContaining("more than 3 redirects");
    }

    @Test
    @Tag("V3.5")
    void aRedirectFromHttpsToHttpIsRefusedButOtherHopsAreNot() {
        okhttp3.HttpUrl https = okhttp3.HttpUrl.parse("https://example.com/a");
        assertThat(HttpSupport.redirectProblem(https, okhttp3.HttpUrl.parse("http://example.com/b"))).contains("https to http");
        assertThat(HttpSupport.redirectProblem(https, okhttp3.HttpUrl.parse("https://other.example.com/b"))).isNull();
        assertThat(HttpSupport.redirectProblem(okhttp3.HttpUrl.parse("http://localhost/a"), okhttp3.HttpUrl.parse("http://localhost/b"))).isNull();
    }

    @Test
    @Tag("V4.8")
    void a429WithRetryAfterIsWaitedForThroughTheSleeper() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "7"));
        server.enqueue(new MockResponse().setBody("ok"));

        HttpSupport.Reply reply = http(false, 2, 4096, Duration.ofSeconds(5)).send(get("/x"), HttpSupport.Retry.UNSENT_AND_STATUS);

        assertThat(reply.bodyText()).isEqualTo("ok");
        assertThat(ctx.slept).containsExactly(Duration.ofSeconds(7));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @Tag("V4.8")
    void serverErrorsBackOffExponentiallyAndStopAtTheRetryLimit() {
        for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(503));

        HttpSupport.Reply reply = http(false, 2, 4096, Duration.ofSeconds(5)).send(get("/x"), HttpSupport.Retry.UNSENT_AND_STATUS);

        assertThat(reply.status()).isEqualTo(503);
        assertThat(ctx.slept).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    @Tag("V4.8")
    void aRetryAfterBeyondTheCapIsNotWaitedFor() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "120"));

        HttpSupport.Reply reply = http(false, 2, 4096, Duration.ofSeconds(5)).send(get("/x"), HttpSupport.Retry.UNSENT_AND_STATUS);

        assertThat(reply.status()).isEqualTo(429);
        assertThat(ctx.slept).isEmpty();
    }

    @Test
    @Tag("V4.8")
    void aRetryAfterGivenAsAnHttpDateIsRelativeToTheClock() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "Thu, 01 Oct 2026 09:00:05 GMT"));
        server.enqueue(new MockResponse().setBody("ok"));

        http(false, 2, 4096, Duration.ofSeconds(5)).send(get("/x"), HttpSupport.Retry.UNSENT_AND_STATUS);

        assertThat(ctx.slept).containsExactly(Duration.ofSeconds(5)); // the test clock is 09:00:00
    }

    @Test
    @Tag("V4.8")
    void retryNoneNeverRetries() {
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThat(http(false, 5, 4096, Duration.ofSeconds(5)).send(get("/x"), HttpSupport.Retry.NONE).status()).isEqualTo(500);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @Tag("V4.7")
    void aRefusedConnectionIsRetriedBecauseNothingWasSent() throws IOException {
        int port = server.getPort();
        server.shutdown();
        HttpSupport http = http(false, 2, 4096, Duration.ofSeconds(2));

        assertThatThrownBy(() -> http.send(new Request.Builder().url("http://localhost:" + port + "/").build(), HttpSupport.Retry.UNSENT_AND_STATUS))
                .isInstanceOf(ToolRefusal.class).isNotInstanceOf(UnknownOutcomeException.class).hasMessageContaining("connection refused");
        assertThat(ctx.slept).hasSize(2);
        server = new MockWebServer(); // so @AfterEach has something to stop
        server.start();
    }

    @Test
    @Tag("V4.7")
    void aResponseThatNeverComesIsNotRetriedBecauseItMayHaveBeenDelivered() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertThatThrownBy(() -> http(false, 2, 4096, Duration.ofMillis(400)).send(get("/x"), HttpSupport.Retry.UNSENT_AND_STATUS))
                .isInstanceOf(UnknownOutcomeException.class).hasMessageContaining("may have been delivered");
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(ctx.slept).isEmpty();
    }

    @Test
    @Tag("V4.7")
    void aConnectionDroppedAfterTheBodyWasSentIsAlsoUnknown() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

        assertThatThrownBy(() -> http(false, 2, 4096, Duration.ofSeconds(2)).send(get("/x"), HttpSupport.Retry.UNSENT_AND_STATUS))
                .isInstanceOf(UnknownOutcomeException.class);
    }

    @Test
    @Tag("V6.10")
    void aSafeToRepeatRequestIsRetriedEvenWhenItMayHaveBeenDelivered() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        server.enqueue(new MockResponse().setBody("second time"));

        HttpSupport.Reply reply = http(false, 2, 4096, Duration.ofSeconds(2)).send(get("/x"), HttpSupport.Retry.ALL);

        assertThat(reply.bodyText()).isEqualTo("second time");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @Tag("V1.7")
    void aServerThatStallsEndsWithinTheTimeout() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> http(false, 0, 4096, Duration.ofMillis(500)).send(get("/x"), HttpSupport.Retry.NONE)).isInstanceOf(ToolRefusal.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    }
}
