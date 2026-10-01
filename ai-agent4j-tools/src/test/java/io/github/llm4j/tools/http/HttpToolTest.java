package io.github.llm4j.tools.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.tools.support.Declared;
import io.github.llm4j.tools.support.EchoServer;
import io.github.llm4j.tools.support.RecordingEffects;
import io.github.llm4j.tools.EffectJournal;
import io.github.llm4j.tools.RequestPath;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpToolTest {

    @TempDir
    Path dir;
    MockWebServer server;
    RecordingEffects ctx;
    Declared declared;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        ctx = new RecordingEffects();
        declared = new Declared(Map.of("API_TOKEN", "tok-SECRETSECRET-1234", "API_KEY", "key-SECRETSECRET-5678"), dir);
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    String base() {
        return "http://localhost:" + server.getPort();
    }

    Tool tool(String options) throws Exception {
        return declared.create("tool Api { use: http  base_url: \"" + base() + "\"  " + options + " }", ctx);
    }

    static String run(Tool tool, Map<String, Object> args) {
        try {
            return tool.execute(args);
        } catch (Exception e) {
            throw new AssertionError("a generic tool threw instead of returning an Error: " + e, e);
        }
    }

    static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    @Tag("V6.1")
    void getSendsTheFixedHeadersAndTheAuthHeaderAndReturnsStatusTypeAndBody() throws Exception {
        server.enqueue(json("{\"ok\":true}"));

        String result = run(tool("auth_header: \"Authorization\"  auth_value: env.API_TOKEN  \"header.Accept\": \"application/vnd.github+json\""),
                Map.of("path", "/repos/x"));

        RecordedRequest r = server.takeRequest();
        assertThat(r.getMethod()).isEqualTo("GET");
        assertThat(r.getPath()).isEqualTo("/repos/x");
        assertThat(r.getHeader("Accept")).isEqualTo("application/vnd.github+json");
        assertThat(r.getHeader("Authorization")).isEqualTo("tok-SECRETSECRET-1234");
        assertThat(result).isEqualTo("HTTP 200 OK\nContent-Type: application/json\n\n{\"ok\":true}");
    }

    @Test
    @Tag("V6.1")
    void aBasePathIsKeptAndAPathIsAppendedToIt() throws Exception {
        server.enqueue(json("{}"));
        declared.create("tool Api { use: http  base_url: \"" + base() + "/api/v2/\" }", ctx).execute(Map.of("path", "/items"));
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/v2/items");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/", "//evil.example/x", "/a/../b", "/a/./b", "/x@evil.example", "x/y", "", "/a b",
            "/a?b=1", "/a#frag", "/a\\b", "/a\u0000b", "/a\nb", "/%2e%2e/etc", "/a%2Fb", "/a%5cb", "/café", "/a//b"})
    @Tag("V6.2")
    @Tag("H2")
    void hostileOrMalformedPathsAreRefusedAndNothingIsSent(String path) throws Exception {
        assertThat(run(tool(""), Map.of("path", path))).startsWith("Error:");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @Tag("V6.2")
    @Tag("H2")
    void theAgentCannotChooseTheHostOrSetHeaders() throws Exception {
        server.enqueue(json("{}"));
        run(tool("auth_header: \"Authorization\"  auth_value: env.API_TOKEN"),
                Map.of("path", "/x", "host", "evil.example", "headers", Map.of("Authorization", "stolen", "Host", "evil.example"),
                        "url", "https://evil.example/"));

        RecordedRequest r = server.takeRequest();
        assertThat(r.getHeader("Authorization")).isEqualTo("tok-SECRETSECRET-1234");
        assertThat(r.getHeader("Host")).startsWith("localhost");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @Tag("V6.3")
    void allowPathsLimitsWhatCanBeCalled() throws Exception {
        Tool t = tool("allow_paths: \"/repos/*, /search/**\"");
        server.enqueue(json("{}"));
        server.enqueue(json("{}"));
        server.enqueue(json("{}"));

        assertThat(run(t, Map.of("path", "/repos/a"))).startsWith("HTTP 200");
        assertThat(run(t, Map.of("path", "/search/a/b/c"))).startsWith("HTTP 200");
        assertThat(run(t, Map.of("path", "/repos/a/b"))).startsWith("Error:").contains("not one this tool may call");
        assertThat(run(t, Map.of("path", "/users/a"))).startsWith("Error:");
        assertThat(run(t, Map.of("path", "/reposX"))).startsWith("Error:");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @Tag("V6.4")
    void queryParametersAreEncodedAndAuthQueryIsAddedButNeverEchoed() throws Exception {
        try (EchoServer echo = new EchoServer()) {
            Tool t = declared.create("tool Api { use: http  base_url: \"http://localhost:" + echo.server.getPort()
                    + "\"  auth_query: \"key\"  auth_value: env.API_KEY }", ctx);

            String result = run(t, Map.of("path", "/search", "query", Map.of("q", "a b&c=d", "tags", List.of("x", "y"))));

            String path = echo.requests.get(0).getPath();
            assertThat(path).contains("q=a%20b%26c%3Dd").contains("tags=x").contains("tags=y").contains("key=key-SECRETSECRET-5678");
            assertThat(result).doesNotContain("key-SECRETSECRET-5678").contains("key=***");
            assertThat(ctx.everything()).doesNotContain("SECRETSECRET");
        }
    }

    @Test
    @Tag("V6.4")
    void authOptionsMustBeConsistent() {
        String base = "tool Api { use: http  base_url: \"https://api.example.com\"  ";
        assertThat(declared.problems(base + "auth_header: \"A\"  auth_query: \"k\"  auth_value: env.API_TOKEN }")).anyMatch(p -> p.contains("not both"));
        assertThat(declared.problems(base + "auth_value: env.API_TOKEN }")).anyMatch(p -> p.contains("auth_value goes with"));
        assertThat(declared.problems(base + "auth_header: \"A\" }")).anyMatch(p -> p.contains("auth_value goes with"));
        assertThat(declared.problems(base + "auth_header: \"A\"  auth_value: \"literal-token\" }")).anyMatch(p -> p.contains("must come from the environment"));
        assertThat(declared.problems(base + "\"header.Authorization\": \"literal\" }")).anyMatch(p -> p.contains("header.Authorization must come from the environment"));
        assertThat(declared.problems(base + "\"header.X-Api-Key\": \"literal\" }")).anyMatch(p -> p.contains("must come from the environment"));
        assertThat(declared.problems(base + "\"header.X-Api-Key\": env.API_KEY  \"header.Accept\": \"application/json\" }")).isEmpty();
    }

    @Test
    @Tag("V6.5")
    void postSendsAJsonObjectOrAPlainStringAndOtherMethodsNeedToBeListed() throws Exception {
        Tool t = tool("methods: \"GET, POST\"");
        server.enqueue(json("{}"));
        server.enqueue(json("{}"));

        run(t, Map.of("path", "/items", "method", "POST", "body", Map.of("name", "n", "tags", List.of("a"))));
        RecordedRequest first = server.takeRequest();
        assertThat(first.getHeader("Content-Type")).startsWith("application/json");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(first.getBody().readUtf8()))
                .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"name\":\"n\",\"tags\":[\"a\"]}"));

        run(t, Map.of("path", "/items", "method", "POST", "body", "plain words"));
        RecordedRequest second = server.takeRequest();
        assertThat(second.getHeader("Content-Type")).startsWith("text/plain");
        assertThat(second.getBody().readUtf8()).isEqualTo("plain words");

        for (String method : List.of("PUT", "PATCH", "DELETE", "TRACE", "CONNECT")) {
            assertThat(run(t, Map.of("path", "/items/1", "method", method))).as(method).startsWith("Error:");
        }
        assertThat(run(t, Map.of("path", "/items", "method", "GET", "body", "x"))).startsWith("Error:").contains("don't take a body");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @Tag("V6.5")
    @Tag("H2")
    void deleteNeedsToBeListedExplicitly() throws Exception {
        assertThat(run(tool(""), Map.of("path", "/items/1", "method", "DELETE"))).startsWith("Error:").contains("not allowed");
        server.enqueue(new MockResponse().setResponseCode(204));
        assertThat(run(tool("methods: \"DELETE\""), Map.of("path", "/items/1", "method", "DELETE"))).startsWith("HTTP 204");
    }

    @Test
    @Tag("V6.6")
    void onlyTextualResponsesAreReturned() throws Exception {
        Tool t = tool("");
        server.enqueue(new MockResponse().setHeader("Content-Type", "image/png").setBody("PNGDATA"));
        assertThat(run(t, Map.of("path", "/img"))).startsWith("Error:").contains("image/png").doesNotContain("PNGDATA");

        server.enqueue(new MockResponse().setHeader("Content-Type", "application/vnd.api+json").setBody("{\"data\":1}"));
        assertThat(run(t, Map.of("path", "/a"))).endsWith("{\"data\":1}");

        server.enqueue(new MockResponse().setHeader("Content-Type", "text/csv").setBody("a,b\n1,2"));
        assertThat(run(t, Map.of("path", "/b"))).endsWith("a,b\n1,2");

        server.enqueue(new MockResponse().setResponseCode(204));
        assertThat(run(t, Map.of("path", "/c"))).startsWith("HTTP 204").doesNotContain("Content-Type");
    }

    @Test
    @Tag("V6.6")
    void aGetErrorStatusIsReturnedAsTextForTheAgentToReadAndALargeBodyIsCut() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("no such thing"));
        assertThat(run(tool(""), Map.of("path", "/missing"))).startsWith("HTTP 404").endsWith("no such thing");

        server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain").setBody("z".repeat(10_000)));
        assertThat(run(tool("max_bytes: 1k"), Map.of("path", "/big"))).contains("z".repeat(1024)).contains("[cut: first 1024 bytes shown");
    }

    @Test
    @Tag("V6.7")
    void getIsRetriedOn5xxAndPostIsNotUnlessIdempotent() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(json("{\"ok\":1}"));
        assertThat(run(tool(""), Map.of("path", "/x"))).contains("{\"ok\":1}");
        assertThat(ctx.slept).hasSize(1);

        server.enqueue(new MockResponse().setResponseCode(503).setBody("busy"));
        String post = run(tool("methods: \"POST\""), Map.of("path", "/x", "method", "POST", "body", "a"));
        assertThat(post).startsWith("Error:").contains("HTTP 503");
        assertThat(server.getRequestCount()).isEqualTo(3);

        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(json("{}"));
        run(tool("methods: \"POST\"  idempotency: true"), Map.of("path", "/y", "method", "POST", "body", "b"));
        assertThat(server.getRequestCount()).isEqualTo(5);
        server.takeRequest(); server.takeRequest(); server.takeRequest();
        assertThat(server.takeRequest().getHeader("Idempotency-Key")).isNotBlank().isEqualTo(server.takeRequest().getHeader("Idempotency-Key"));
    }

    @Test
    @Tag("V6.8")
    void getWritesNoEffectRecordAndPostDoesAndIsReplayed() throws Exception {
        server.enqueue(json("{}"));
        run(tool(""), Map.of("path", "/read"));
        assertThat(ctx.journal().all()).isEmpty();

        server.enqueue(json("{\"created\":1}"));
        Tool t = tool("methods: \"POST\"");
        assertThat(run(t, Map.of("path", "/create", "method", "POST", "body", "x"))).contains("created");
        assertThat(ctx.journal().all().values()).extracting(EffectJournal.Entry::kind).containsExactly("effect_done");

        String resumed = run(tool("methods: \"POST\""), Map.of("path", "/create", "method", "POST", "body", "x"));
        assertThat(resumed).contains("already done");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @Tag("V6.8")
    void aFailedPostIsRecordedAsFailedSoItCanBeRetried() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json").setBody("{\"error\":\"bad\"}"));
        Tool t = tool("methods: \"POST\"");
        assertThat(run(t, Map.of("path", "/c", "method", "POST", "body", "x"))).startsWith("Error: HTTP 422").contains("{\"error\":\"bad\"}");
        assertThat(ctx.journal().all().values()).extracting(EffectJournal.Entry::kind).containsExactly("effect_failed");
    }

    @Test
    @Tag("V6.9")
    void aBaseUrlThatPointsInsideTheNetworkIsALoadError() {
        assertThat(declared.problems("tool Api { use: http  base_url: \"https://10.0.0.5/\" }")).anyMatch(p -> p.contains("base_url") && p.contains("private"));
        assertThat(declared.problems("tool Api { use: http  base_url: \"https://169.254.169.254/\" }")).anyMatch(p -> p.contains("link-local"));
        assertThat(declared.problems("tool Api { use: http  base_url: \"https://[fd00::1]/\" }")).anyMatch(p -> p.contains("private"));
        assertThat(declared.problems("tool Api { use: http  base_url: \"https://10.0.0.5/\"  allow_private: true }")).isEmpty();
        assertThat(declared.problems("tool Api { use: http  base_url: \"http://example.com/\" }")).anyMatch(p -> p.contains("only https"));
        assertThat(declared.problems("tool Api { use: http  base_url: \"https://u:p@example.com/\" }")).anyMatch(p -> p.contains("user name"));
        assertThat(declared.problems("tool Api { use: http  base_url: \"https://example.com/?a=1\" }")).anyMatch(p -> p.contains("query"));
        assertThat(declared.problems("tool Api { use: http  base_url: \"not a url\" }")).anyMatch(p -> p.contains("not a valid URL"));
        assertThat(declared.problems("tool Api { use: http  base_url: \"https://example.com\"  methods: \"GET, TRACE\" }")).anyMatch(p -> p.contains("TRACE"));
        assertThat(declared.problems("tool Api { use: http  base_url: \"https://example.com\"  allow_paths: \"repos/*\" }")).anyMatch(p -> p.contains("must start with /"));
        assertThat(declared.problems("tool Api { use: http }")).anyMatch(p -> p.contains("needs base_url"));
    }

    @Test
    @Tag("V6.10")
    void aStalledPostIsNotRetriedAndStaysPendingWhileAGetIsRetriedAfterADrop() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        Tool slow = declared.create("tool Api { use: http  base_url: \"" + base() + "\"  methods: \"POST\"  timeout: 500ms }", ctx);
        assertThat(run(slow, Map.of("path", "/x", "method", "POST", "body", "a"))).contains("may have been delivered");
        assertThat(ctx.journal().all().values()).extracting(EffectJournal.Entry::kind).containsExactly("effect_pending");
        assertThat(server.getRequestCount()).isEqualTo(1);

        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        server.enqueue(json("{\"second\":1}"));
        assertThat(run(tool("timeout: 2s"), Map.of("path", "/y"))).contains("{\"second\":1}");
    }

    @Test
    @Tag("H2")
    void aRedirectToAnInternalAddressIsNotFollowedAndAFollowedOneIsRefused() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "http://169.254.169.254/latest/meta-data/"));
        assertThat(run(tool(""), Map.of("path", "/r"))).startsWith("HTTP 302");
        assertThat(server.getRequestCount()).isEqualTo(1);

        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "http://169.254.169.254/latest/meta-data/"));
        // Refused first for being plain http ...
        assertThat(run(tool("follow_redirects: true"), Map.of("path", "/r"))).startsWith("Error:").contains("only https");
        assertThat(server.getRequestCount()).isEqualTo(2);

        // ... and with http permitted, for being an internal address.
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "http://169.254.169.254/latest/meta-data/"));
        assertThat(run(tool("follow_redirects: true  allow_http: true"), Map.of("path", "/r"))).startsWith("Error:").contains("link-local");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    @Tag("F2")
    void generatedPathsAreOnlyEverAcceptedWhenTheyStayBelowTheBase() {
        io.github.llm4j.tools.support.Fuzz.run("request-paths", random -> {
            String alphabet = "ab/./..%2eE5cC@:?#\\ \u0000\né．";
            StringBuilder sb = new StringBuilder(random.nextBoolean() ? "/" : "");
            for (int i = random.nextInt(12); i >= 0; i--) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String path = sb.toString();
            try {
                String ok = RequestPath.validate(path);
                assertThat(ok).startsWith("/").doesNotContain("//").doesNotContain("\\")
                        .doesNotContain("?").doesNotContain("#").doesNotContain("@").doesNotContain(" ");
                assertThat(ok.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)).isTrue();
                assertThat(java.util.Arrays.asList(ok.split("/"))).doesNotContain(".", "..");
                assertThat(ok.toLowerCase()).doesNotContain("%2e").doesNotContain("%2f").doesNotContain("%5c");
                okhttp3.HttpUrl built = okhttp3.HttpUrl.parse("http://base.example/api").newBuilder().encodedPath("/api" + ok).build();
                assertThat(built.host()).isEqualTo("base.example");
                assertThat(built.encodedPath()).startsWith("/api/");
            } catch (io.github.llm4j.tools.ToolRefusal refused) {
                // refused: fine
            }
        });
    }
}
