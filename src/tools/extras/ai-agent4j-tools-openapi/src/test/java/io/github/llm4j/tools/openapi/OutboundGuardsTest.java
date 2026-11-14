package io.github.llm4j.tools.openapi;

import static io.github.llm4j.tools.openapi.Fixtures.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every request an openapi tool makes passes the shared HTTP layer's address rules, timeouts and
 * retry rules (phase 36a; guards G69, G70 and G71). The server is a real HTTPS server with a test
 * authority, and verification is on.
 */
class OutboundGuardsTest {

    @TempDir Path dir;
    Fixtures.Tls tls;
    final Fixtures.Context context = new Fixtures.Context();

    @BeforeEach
    void start() throws IOException {
        tls = new Fixtures.Tls();
    }

    @AfterEach
    void stop() throws IOException {
        tls.close();
    }

    private Tool tool(Map<String, String> options) throws Exception {
        return tls.kind.create("Op", options, dir, context);
    }

    /** A description whose only server is {@code serverUrl}. */
    private Path spec(String serverUrl, String extra) throws IOException {
        String json =
                """
                {"openapi":"3.0.3","info":{"title":"t","version":"1"},
                 "servers":[{"url":"%s"}],
                 "paths":{"/things/{id}":{"get":{"operationId":"getThing",
                   "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string"}}],
                   "responses":{"200":{"description":"ok","content":{"application/json":{"schema":%s}}}}},
                  "post":{"operationId":"makeThing",
                   "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string"}}],
                   "responses":{"200":{"description":"ok"}}}}}}
                """
                        .formatted(serverUrl, extra);
        Path file = dir.resolve("api.json");
        Files.writeString(file, json);
        return file;
    }

    private static final String PLAIN = "{\"type\":\"object\"}";

    // ── G69: the address rules ───────────────────────────────────────────────────────────────

    @Test
    void aServerTheDescriptionNamesIsNotTrustedToBeLoopback() throws Exception {
        spec(tls.url(""), PLAIN);
        Tool tool = tool(options("spec", "api.json", "operation", "getThing"));

        String answer = tool.execute(Map.of("id", "1"));

        assertThat(answer).startsWith("Error:").contains("loopback");
        assertThat(tls.server.getRequestCount()).isZero();
    }

    @Test
    void aMetadataAddressInTheDescriptionIsRefused() throws Exception {
        spec("https://169.254.169.254/latest", PLAIN);
        Tool tool = tool(options("spec", "api.json", "operation", "getThing"));

        assertThat(tool.execute(Map.of("id", "1"))).startsWith("Error:").contains("link-local");
    }

    @Test
    void aPrivateAddressInTheDescriptionIsRefused() throws Exception {
        spec("https://10.0.0.5/api", PLAIN);
        Tool tool = tool(options("spec", "api.json", "operation", "getThing"));

        assertThat(tool.execute(Map.of("id", "1"))).startsWith("Error:").contains("private");
    }

    @Test
    void theAddressTheDeclarationNamesIsTheAuthorsChoiceAndWorks() throws Exception {
        spec("https://api.example.invalid", PLAIN);
        tls.server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"id\":\"1\"}"));
        Tool tool =
                tool(
                        options(
                                "spec", "api.json", "operation", "getThing",
                                "base_url", tls.url("/v1")));

        String answer = tool.execute(Map.of("id", "1"));

        assertThat(answer).contains("HTTP 200").contains("{\"id\":\"1\"}");
        assertThat(tls.next().getPath()).isEqualTo("/v1/things/1");
    }

    @Test
    void aRedirectToAPrivateAddressIsRefusedAndNeverFollowed() throws Exception {
        spec("https://api.example.invalid", PLAIN);
        tls.server.enqueue(
                new MockResponse().setResponseCode(302).setHeader("Location", "https://169.254.169.254/x"));
        Tool tool =
                tool(
                        options(
                                "spec", "api.json", "operation", "getThing",
                                "base_url", tls.url(""), "follow_redirects", "true"));

        assertThat(tool.execute(Map.of("id", "1"))).startsWith("Error:").contains("refused");
        assertThat(tls.server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void aRedirectFromHttpsToPlainHttpIsRefused() throws Exception {
        spec("https://api.example.invalid", PLAIN);
        tls.server.enqueue(
                new MockResponse().setResponseCode(301).setHeader("Location", "http://localhost:1/x"));
        Tool tool =
                tool(
                        options(
                                "spec", "api.json", "operation", "getThing",
                                "base_url", tls.url(""), "follow_redirects", "true"));

        assertThat(tool.execute(Map.of("id", "1"))).startsWith("Error:").contains("https to http");
    }

    @Test
    void aCertificateFromAnAuthorityNobodyTrustsIsNotAccepted() throws Exception {
        // the default kind trusts the JDK's authorities, not the test authority: the handshake must fail
        spec("https://api.example.invalid", PLAIN);
        tls.server.enqueue(new MockResponse().setBody("{}"));
        Tool tool =
                new OpenApiKind()
                        .create(
                                "Op",
                                options(
                                        "spec", "api.json", "operation", "getThing",
                                        "base_url", tls.url(""), "retries", "0"),
                                dir,
                                context);

        assertThat(tool.execute(Map.of("id", "1"))).startsWith("Error:").contains("TLS failed");
    }

    // ── G70: the parser never fetches on its own ─────────────────────────────────────────────

    @Test
    void anExternalReferenceToALoopbackAddressIsRefusedBeforeAnyRequest() throws Exception {
        spec("https://api.example.invalid", "{\"$ref\":\"" + tls.url("/ext.json") + "\"}");

        assertThatThrownBy(() -> tool(options("spec", "api.json", "operation", "getThing", "base_url", tls.url(""))))
                .hasMessageContaining("loopback");
        assertThat(tls.server.getRequestCount()).isZero();
    }

    @Test
    void anExternalReferenceToAMetadataAddressIsRefused() throws Exception {
        spec("https://api.example.invalid", "{\"$ref\":\"https://169.254.169.254/latest/meta-data\"}");

        assertThatThrownBy(
                        () ->
                                tool(
                                        options(
                                                "spec", "api.json", "operation", "getThing",
                                                "base_url", tls.url(""))))
                .hasMessageContaining("link-local");
    }

    @Test
    void aRemoteDescriptionIsFetchedThroughTheGuardedLayerAndItsReferencesToo() throws Exception {
        String ext = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}";
        tls.server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                Files.readString(spec("https://localhost:" + tls.server.getPort() + "/v1", "{\"$ref\":\"schemas/thing.json\"}"))));
        tls.server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(ext));
        tls.server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"name\":\"a\"}"));
        Tool tool =
                tool(
                        options(
                                "spec", tls.url("/openapi.json"), "operation", "getThing",
                                "base_url", tls.url("/v1")));

        assertThat(tool.execute(Map.of("id", "7"))).contains("{\"name\":\"a\"}");
        assertThat(tls.next().getPath()).isEqualTo("/openapi.json");
        assertThat(tls.next().getPath()).isEqualTo("/schemas/thing.json");
        assertThat(tls.next().getPath()).isEqualTo("/v1/things/7");
    }

    @Test
    void aFileReferenceOutsideTheDescriptionsFolderIsRefused() throws Exception {
        Path inner = Files.createDirectories(dir.resolve("specs"));
        Files.writeString(dir.resolve("secret.json"), "{\"type\":\"string\"}");
        Files.writeString(
                inner.resolve("api.json"),
                Files.readString(spec("https://api.example.invalid", "{\"$ref\":\"../secret.json\"}")));

        assertThatThrownBy(
                        () ->
                                tool(
                                        options(
                                                "spec", "specs/api.json", "operation", "getThing",
                                                "base_url", tls.url(""))))
                .hasMessageContaining("outside the folder");
    }

    @Test
    void aSymbolicLinkCannotCarryAFileReferenceOutOfTheFolder() throws Exception {
        Path inner = Files.createDirectories(dir.resolve("specs"));
        Path outside = Files.writeString(dir.resolve("outside.json"), "{\"type\":\"string\"}");
        Files.createSymbolicLink(inner.resolve("link.json"), outside);
        Files.writeString(
                inner.resolve("api.json"),
                Files.readString(spec("https://api.example.invalid", "{\"$ref\":\"link.json\"}")));

        assertThatThrownBy(
                        () ->
                                tool(
                                        options(
                                                "spec", "specs/api.json", "operation", "getThing",
                                                "base_url", tls.url(""))))
                .hasMessageContaining("outside the folder");
    }

    @Test
    void aReferenceInsideTheFolderIsPastedIn() throws Exception {
        Files.writeString(dir.resolve("thing.json"), "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}}}");
        spec("https://api.example.invalid", "{\"$ref\":\"thing.json\"}");
        tls.server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"n\":1}"));

        Tool tool = tool(options("spec", "api.json", "operation", "getThing", "base_url", tls.url("")));

        assertThat(tool.execute(Map.of("id", "1"))).contains("{\"n\":1}");
    }

    @Test
    void aCircularExternalReferenceIsRefusedNotFollowedForever() throws Exception {
        Files.writeString(dir.resolve("a.json"), "{\"$ref\":\"b.json\"}");
        Files.writeString(dir.resolve("b.json"), "{\"$ref\":\"a.json\"}");
        spec("https://api.example.invalid", "{\"$ref\":\"a.json\"}");

        assertThatThrownBy(() -> tool(options("spec", "api.json", "operation", "getThing", "base_url", tls.url(""))))
                .hasMessageContaining("deep");
    }

    // ── G71: timeouts and honest retries ─────────────────────────────────────────────────────

    @Test
    void aSlowAnswerTimesOut() throws Exception {
        spec("https://api.example.invalid", PLAIN);
        tls.server.enqueue(new MockResponse().setBody("{}").setSocketPolicy(SocketPolicy.NO_RESPONSE));
        Tool tool =
                tool(
                        options(
                                "spec", "api.json", "operation", "getThing",
                                "base_url", tls.url(""), "timeout", "300ms", "retries", "0"));

        assertThat(tool.execute(Map.of("id", "1"))).startsWith("Error:").contains("timed out");
    }

    @Test
    void retryAfterInSecondsIsHonoured() throws Exception {
        spec("https://api.example.invalid", PLAIN);
        tls.server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "7"));
        tls.server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
        Tool tool = tool(options("spec", "api.json", "operation", "getThing", "base_url", tls.url("")));

        assertThat(tool.execute(Map.of("id", "1"))).contains("HTTP 200");
        assertThat(context.slept).containsExactly(java.time.Duration.ofSeconds(7));
    }

    @Test
    void retryAfterAsAnHttpDateIsHonoured() throws Exception {
        spec("https://api.example.invalid", PLAIN);
        // the context's clock is fixed at 09:00:00 UTC: this date is 12 seconds later
        tls.server.enqueue(
                new MockResponse()
                        .setResponseCode(503)
                        .setHeader("Retry-After", "Thu, 01 Oct 2026 09:00:12 GMT"));
        tls.server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
        Tool tool = tool(options("spec", "api.json", "operation", "getThing", "base_url", tls.url("")));

        assertThat(tool.execute(Map.of("id", "1"))).contains("HTTP 200");
        assertThat(context.slept).containsExactly(java.time.Duration.ofSeconds(12));
    }

    @Test
    void aPostThatMayHaveBeenDeliveredIsNotRepeated() throws Exception {
        spec("https://api.example.invalid", PLAIN);
        tls.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        Tool tool =
                tool(
                        options(
                                "spec", "api.json", "operation", "makeThing",
                                "base_url", tls.url(""), "timeout", "300ms"));

        String answer = tool.execute(Map.of("id", "1"));

        assertThat(answer).contains("may have been delivered");
        assertThat(tls.server.getRequestCount()).isEqualTo(1);
    }
}
