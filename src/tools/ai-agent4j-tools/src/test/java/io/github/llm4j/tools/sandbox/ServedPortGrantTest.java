package io.github.llm4j.tools.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.tools.HttpKind;
import io.github.llm4j.tools.NetPolicy;
import io.github.llm4j.tools.support.RecordingEffects;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, D7 and G53: an {@code http} tool that {@code reaches:} a sandbox calls the program it
 * serves on {@code 127.0.0.1}, on the port of a live lease and on no other port or host, only
 * while the lease lives; every reach is in the audit.
 */
class ServedPortGrantTest {

    static final long FAR = Long.MAX_VALUE / 2;

    @TempDir Path dir;
    final RecordingEffects effects = new RecordingEffects();
    MockWebServer served;
    Path leases;

    @BeforeEach
    void serve() throws Exception {
        served = new MockWebServer();
        served.start(InetAddress.getByName("127.0.0.1"), 0);
        leases = Files.createDirectories(dir.resolve(".loom-workspace/Data/leases"));
    }

    @AfterEach
    void stop() throws Exception {
        served.shutdown();
    }

    void lease(String handle, String owner, int port, long deadline) throws Exception {
        Files.writeString(
                leases.resolve(handle + ".json"),
                "{\"handle\":\"" + handle + "\",\"owner\":\"" + owner + "\",\"port\":" + port + ",\"deadline\":" + deadline + "}");
    }

    Map<String, String> options(String... pairs) {
        Map<String, String> o = new HashMap<>(Map.of("reaches", "Py", "reaches.workspace", "Data"));
        for (int i = 0; i < pairs.length; i += 2) o.put(pairs[i], pairs[i + 1]);
        return o;
    }

    String get(Map<String, String> o, Map<String, Object> args) throws Exception {
        Tool t = new HttpKind().create("App", o, dir, effects);
        return t.execute(args);
    }

    String get(String path) throws Exception {
        return get(options(), Map.of("path", path));
    }

    @Test
    void aLiveLeaseIsReachedOnItsPortAndTheReachIsAudited() throws Exception {
        lease("llm4j-serve-a", "Py", served.getPort(), FAR);
        served.enqueue(new MockResponse().setBody("{\"ok\":true}").setHeader("Content-Type", "application/json"));
        assertThat(get("/health")).startsWith("HTTP 200").contains("ok");
        assertThat(served.takeRequest().getRequestUrl().port()).isEqualTo(served.getPort());
        assertThat(effects.audited("served_port_reached")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("port", served.getPort()).containsEntry("owner", "Py"));
    }

    @Test
    void withoutALiveLeaseNothingIsReached() throws Exception {
        assertThat(get("/health")).contains("serves nothing now");
        lease("llm4j-serve-old", "Py", served.getPort(), 1);
        assertThat(get("/health")).contains("serves nothing now");
        lease("llm4j-serve-web", "Web", served.getPort(), FAR);
        assertThat(get("/health")).as("another sandbox's lease").contains("serves nothing now");
        assertThat(served.getRequestCount()).isZero();
    }

    @Test
    void twoServedProgramsNeedAHandle() throws Exception {
        lease("llm4j-serve-a", "Py", served.getPort(), FAR);
        lease("llm4j-serve-b", "Py", served.getPort() + 1, FAR);
        assertThat(get("/")).contains("name one with handle");
        served.enqueue(new MockResponse().setBody("a").setHeader("Content-Type", "text/plain"));
        assertThat(get(options(), Map.of("path", "/", "handle", "llm4j-serve-a"))).startsWith("HTTP 200");
        assertThat(get(options(), Map.of("path", "/", "handle", "llm4j-serve-c"))).contains("serves no program llm4j-serve-c");
        assertThat(get(options(), Map.of("path", "/", "handle", "../x"))).contains("not one this tool gave out");
    }

    @Test
    void aRedirectToAnotherPortOrHostIsRefused() throws Exception {
        lease("llm4j-serve-a", "Py", served.getPort(), FAR);
        served.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:22/"));
        assertThat(get(options("follow_redirects", "true"), Map.of("path", "/"))).contains("port 22 is not the port");
        served.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:" + served.getPort() + "/"));
        assertThat(get(options("follow_redirects", "true"), Map.of("path", "/"))).contains("nothing else");
        served.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/"));
        assertThat(get(options("follow_redirects", "true"), Map.of("path", "/"))).contains("nothing else");
    }

    @Test
    void theGrantIsExactlyOnePortOnOneAddress() {
        NetPolicy p = NetPolicy.servedPort(new NetPolicy.Rules(false, false, Set.of(), false), h -> List.of(InetAddress.getLoopbackAddress()), port -> port == 41000);
        assertThat(p.checkUrl(HttpUrl.get("http://127.0.0.1:41000/x"))).isNull();
        assertThat(p.checkUrl(HttpUrl.get("http://127.0.0.1:41001/x"))).contains("not the port");
        assertThat(p.checkUrl(HttpUrl.get("http://[::1]:41000/x"))).contains("nothing else");
        assertThat(p.checkUrl(HttpUrl.get("http://10.0.0.1:41000/x"))).contains("nothing else");
        NetPolicy plain = new NetPolicy(new NetPolicy.Rules(false, false, Set.of(), false), h -> List.of(InetAddress.getLoopbackAddress()), "api.example.com");
        assertThat(plain.checkUrl(HttpUrl.get("http://127.0.0.1:41000/x"))).as("no grant, no loopback").isNotNull();
    }

    @Test
    void reachesTakesThePlaceOfABaseUrlAndOfHostLists() {
        HttpKind kind = new HttpKind();
        assertThat(kind.check(options("base_url", "https://api.example.com"), dir)).contains("goes away with reaches");
        assertThat(kind.check(options("hosts", "example.com"), dir)).contains("don't go with reaches");
        assertThat(kind.check(Map.of(), dir)).contains("needs base_url");
        assertThat(kind.check(options(), dir)).isNull();
        assertThat(kind.references()).containsEntry("reaches", "sandbox");
        assertThat(kind.profile(options()).note()).contains("Py serves on this machine");
    }
}
