package io.github.llm4j.tools.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.tools.HttpSupport;
import io.github.llm4j.tools.NetPolicy;
import io.github.llm4j.tools.ToolRefusal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The reference provider against a recorded answer of Brave's API shape, on a local server. */
class BraveSearchTest {

    MockWebServer server;
    HttpSupport http;
    BraveSearch brave;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        http = new HttpSupport(
                new NetPolicy(new NetPolicy.Rules(false, false, Set.of(), false), NetPolicy.Resolver.SYSTEM, "localhost"),
                EffectContext.noop(), Duration.ofSeconds(5), 1 << 20, 0);
        brave = new BraveSearch(server.url("/res/v1/web/search"));
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void itSendsTheQueryAndTheKeyAndReadsTheResults() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"web":{"results":[
                  {"title":"Records","url":"https://docs.example/records","description":"About records"},
                  {"title":"No address"},
                  {"title":"Second","url":"https://b.example/","description":"b"},
                  {"title":"Third","url":"https://c.example/"}]}}
                """));
        List<SearchHit> hits = brave.search("java records", 2, "test-key-0000", http);
        assertThat(hits).containsExactly(
                new SearchHit("Records", "https://docs.example/records", "About records"),
                new SearchHit("Second", "https://b.example/", "b"));
        RecordedRequest r = server.takeRequest();
        assertThat(r.getHeader("X-Subscription-Token")).isEqualTo("test-key-0000");
        assertThat(r.getRequestUrl().queryParameter("q")).isEqualTo("java records");
        assertThat(r.getRequestUrl().queryParameter("count")).isEqualTo("2");
    }

    @Test
    void aRefusalFromTheProviderIsSaidAndNoResultsAreMadeUp() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));
        assertThatThrownBy(() -> brave.search("q", 5, "test-key-0000", http))
                .isInstanceOf(ToolRefusal.class)
                .hasMessageContaining("HTTP 401");
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
        assertThat(brave.search("q", 5, "test-key-0000", http)).isEmpty();
    }

    @Test
    void itNamesItselfAndItsOneHost() {
        BraveSearch real = new BraveSearch();
        assertThat(real.name()).isEqualTo("brave");
        assertThat(real.host()).isEqualTo("api.search.brave.com");
        assertThat(real.needsKey()).isTrue();
    }
}
