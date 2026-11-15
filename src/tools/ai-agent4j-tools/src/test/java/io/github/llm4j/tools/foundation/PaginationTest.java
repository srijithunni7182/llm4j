package io.github.llm4j.tools.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.tools.HttpSupport;
import io.github.llm4j.tools.NetPolicy;
import io.github.llm4j.tools.Options;
import io.github.llm4j.tools.Pagination;
import io.github.llm4j.tools.ToolRefusal;
import io.github.llm4j.tools.support.RecordingEffects;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Phase 36b, D5 and G91: bounded, read-only pagination that sends every page through the same guards. */
class PaginationTest {

    MockWebServer server = new MockWebServer();
    final RecordingEffects ctx = new RecordingEffects();

    @BeforeEach
    void start() throws Exception {
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private HttpSupport http(long cap) {
        return new HttpSupport(new NetPolicy(new NetPolicy.Rules(true, false, Set.of(), false), NetPolicy.Resolver.SYSTEM, "localhost"), ctx, Duration.ofSeconds(5), cap, 0);
    }

    private Request first(String path) {
        return new Request.Builder().url(server.url(path)).build();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static Pagination of(String... pairs) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return Pagination.parse(new Options(m));
    }

    @Test
    void noStyleIsOnePage() {
        assertThat(of().enabled()).isFalse();
    }

    @Test
    void linkFollowsRelNextUntilThereIsNone() throws Exception {
        server.enqueue(json("[1]").setHeader("Link", "<" + server.url("/p2") + ">; rel=\"next\", <" + server.url("/p9") + ">; rel=\"last\""));
        server.enqueue(json("[2]").setHeader("Link", "<" + server.url("/p3") + ">; rel=\"next\""));
        server.enqueue(json("[3]"));

        HttpSupport.Reply all = of("paginate", "link").collect(http(1 << 20), first("/p1"), HttpSupport.Retry.ALL);

        assertThat(all.bodyText()).contains("[1]").contains("[2]").contains("[3]");
        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(all.truncated()).isFalse();
    }

    @Test
    void cursorSendsTheNextCursorBackInANamedParameter() throws Exception {
        server.enqueue(json("{\"items\":[1],\"meta\":{\"next\":\"abc\"}}"));
        server.enqueue(json("{\"items\":[2],\"meta\":{\"next\":null}}"));

        of("paginate", "cursor", "cursor_path", "meta.next", "cursor_param", "after").collect(http(1 << 20), first("/list?x=1"), HttpSupport.Retry.ALL);

        server.takeRequest();
        var second = server.takeRequest();
        assertThat(second.getRequestUrl().queryParameter("after")).isEqualTo("abc");
        assertThat(second.getRequestUrl().queryParameter("x")).isEqualTo("1");
    }

    @Test
    void pageIncrementsUntilAPageIsEmpty() throws Exception {
        server.enqueue(json("[1]"));
        server.enqueue(json("[2]"));
        server.enqueue(json("[]"));

        of("paginate", "page", "page_param", "page").collect(http(1 << 20), first("/list"), HttpSupport.Retry.ALL);

        server.takeRequest();
        assertThat(server.takeRequest().getRequestUrl().queryParameter("page")).isEqualTo("2");
        assertThat(server.takeRequest().getRequestUrl().queryParameter("page")).isEqualTo("3");
    }

    @Test
    void aServerThatNeverStopsIsStoppedAtMaxPages() throws Exception {
        for (int i = 0; i < 20; i++) server.enqueue(json("[1]").setHeader("Link", "<" + server.url("/n") + ">; rel=\"next\""));

        HttpSupport.Reply all = of("paginate", "link", "max_pages", "3").collect(http(1 << 20), first("/p"), HttpSupport.Retry.ALL);

        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(all.truncated()).isTrue();
    }

    @Test
    void theTotalIsCappedAndSaysSo() throws Exception {
        server.enqueue(json("x".repeat(60)).setHeader("Link", "<" + server.url("/n") + ">; rel=\"next\""));
        server.enqueue(json("y".repeat(60)));

        HttpSupport.Reply all = of("paginate", "link").collect(http(100), first("/p"), HttpSupport.Retry.ALL);

        assertThat(all.body().length).isLessThanOrEqualTo(100);
        assertThat(all.truncated()).isTrue();
    }

    @Test
    void aLinkToAMetadataAddressIsNotFollowed() throws Exception {
        server.enqueue(json("[1]").setHeader("Link", "<http://169.254.169.254/next>; rel=\"next\""));

        assertThat(of("paginate", "link").collect(http(1 << 20), first("/p"), HttpSupport.Retry.ALL).bodyText()).isEqualTo("[1]");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void anAnswerThatIsNotJsonEndsACursorWalk() throws Exception {
        server.enqueue(new MockResponse().setBody("plain"));

        HttpSupport.Reply all = of("paginate", "cursor", "cursor_path", "n", "cursor_param", "c").collect(http(1 << 20), first("/p"), HttpSupport.Retry.ALL);

        assertThat(all.bodyText()).isEqualTo("plain");
    }

    @Test
    void theOptionsAreChecked() {
        assertThatThrownBy(() -> of("paginate", "cursor")).hasMessageContaining("cursor_path");
        assertThatThrownBy(() -> of("paginate", "page")).hasMessageContaining("page_param");
        assertThatThrownBy(() -> of("paginate", "sideways")).hasMessageContaining("link, cursor or page");
        assertThatThrownBy(() -> of("paginate", "link", "max_pages", "500")).hasMessageContaining("max_pages");
    }

    @Test
    void itIsRefusedOnAnythingThatChangesState() {
        assertThatThrownBy(() -> of("paginate", "link").requireRead("POST")).hasMessageContaining("only for reads");
        of("paginate", "link").requireRead("GET");
        of().requireRead("POST");
    }

    @Test
    void aNextLinkToAnotherHostIsNeverFollowedBecauseTheRequestCarriesTheCredential() throws Exception {
        server.enqueue(json("[1]").setHeader("Link", "<https://attacker.example/steal>; rel=\"next\""));

        HttpSupport.Reply all = of("paginate", "link").collect(http(1 << 20), first("/p1"), HttpSupport.Retry.ALL);

        assertThat(all.bodyText()).isEqualTo("[1]");
        assertThat(server.getRequestCount()).as("no second request, to anyone").isEqualTo(1);
    }

    @Test
    void aNextLinkWithAnotherPortOrSchemeIsNotFollowedEither() throws Exception {
        server.enqueue(json("[1]").setHeader("Link", "<http://" + server.getHostName() + ":" + (server.getPort() + 1) + "/p2>; rel=\"next\""));

        of("paginate", "link").collect(http(1 << 20), first("/p1"), HttpSupport.Retry.ALL);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
