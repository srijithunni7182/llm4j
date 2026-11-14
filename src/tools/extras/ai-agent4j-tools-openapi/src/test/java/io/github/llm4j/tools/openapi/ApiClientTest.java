package io.github.llm4j.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.tools.CredentialRejected;
import io.github.llm4j.tools.HttpSupport;
import io.github.llm4j.tools.NetOptions;
import io.github.llm4j.tools.NetPolicy;
import io.github.llm4j.tools.Options;
import io.github.llm4j.tools.ToolRefusal;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How one call is made (phase 36a, D3 and D8), against a plain local server so each request can be read back. */
class ApiClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    MockWebServer server = new MockWebServer();
    final Fixtures.Context ctx = new Fixtures.Context();

    @BeforeEach
    void start() throws IOException {
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private ApiClient client(Credential credential, String... options) {
        Options o = new Options(Fixtures.options(options));
        NetOptions net = NetOptions.parse(o, 0, true);
        HttpUrl base = server.url("/base/v1");
        HttpSupport http =
                new HttpSupport(
                        new NetPolicy(net.rules(), NetPolicy.Resolver.SYSTEM, base.host()),
                        ctx,
                        Duration.ofSeconds(5),
                        1 << 20,
                        0);
        return new ApiClient("Tool", base, net, credential, new Answers.Settings(1 << 16, null, 1 << 16), http, List.of());
    }

    private static ApiOperation.Parameter p(String name, String where, boolean required, String type) throws Exception {
        return new ApiOperation.Parameter(name, name, where, required, JSON.readTree("{\"type\":\"" + type + "\"}"));
    }

    private RecordedRequest sent() throws InterruptedException {
        RecordedRequest r = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(r).isNotNull();
        return r;
    }

    private static final MockResponse OK = new MockResponse().setHeader("Content-Type", "application/json").setBody("{}");

    @Test
    void theHostIsTheBaseHost() {
        assertThat(client(Credential.NONE).host()).isEqualTo("localhost");
    }

    @Test
    void aListParameterIsSentOncePerItemAndAHeaderParameterAsAHeader() throws Exception {
        server.enqueue(OK);
        var op = new ApiOperation("o", "GET", "/items", "", List.of(p("tag", "query", false, "array"), p("X-Trace", "header", false, "string")), null);

        client(Credential.NONE).call(op, Map.of("tag", List.of("a", "b"), "X-Trace", "t-1"), null);

        RecordedRequest r = sent();
        assertThat(r.getRequestUrl().queryParameterValues("tag")).containsExactly("a", "b");
        assertThat(r.getHeader("X-Trace")).isEqualTo("t-1");
        assertThat(r.getPath()).startsWith("/base/v1/items");
        assertThat(r.getHeader("Accept")).contains("application/json");
    }

    @Test
    void aCredentialInTheQueryIsSentThereAndNeverAsAHeader() throws Exception {
        server.enqueue(OK);
        var op = new ApiOperation("o", "GET", "/items", "", List.of(), null);

        client(new Credential(null, "api_key", "test-key-0000", null)).call(op, Map.of(), null);

        RecordedRequest r = sent();
        assertThat(r.getRequestUrl().queryParameter("api_key")).isEqualTo("test-key-0000");
        assertThat(r.getHeader("Authorization")).isNull();
    }

    @Test
    void aFormBodyIsSentAsFormFields() throws Exception {
        server.enqueue(OK);
        var form = new ApiOperation.Body(false, "application/x-www-form-urlencoded", JSON.readTree("{\"type\":\"object\"}"));
        var op = new ApiOperation("o", "POST", "/f", "", List.of(), form);

        client(Credential.NONE).call(op, Map.of("body", Map.of("name", "rex & co", "n", 2)), null);

        RecordedRequest r = sent();
        assertThat(r.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(r.getBody().readUtf8()).contains("name=rex+%26+co").contains("n=2");
    }

    @Test
    void aFormBodyThatIsNotAnObjectIsRefused() throws Exception {
        var form = new ApiOperation.Body(false, "application/x-www-form-urlencoded", JSON.readTree("{\"type\":\"object\"}"));
        var op = new ApiOperation("o", "POST", "/f", "", List.of(), form);

        assertThatThrownBy(() -> client(Credential.NONE).call(op, Map.of("body", "text"), null)).hasMessageContaining("object of fields");
    }

    @Test
    void aJsonBodyGivenAsTextForAnObjectIsSentAsIsWhenItIsJsonAndRefusedWhenItIsNot() throws Exception {
        server.enqueue(OK);
        var body = new ApiOperation.Body(false, "application/json", JSON.readTree("{\"type\":\"object\"}"));
        var op = new ApiOperation("o", "POST", "/j", "", List.of(), body);

        client(Credential.NONE).call(op, Map.of("body", "{\"a\": 1}"), null);
        assertThat(sent().getBody().readUtf8()).isEqualTo("{\"a\": 1}");

        assertThatThrownBy(() -> client(Credential.NONE).call(op, Map.of("body", "not json"), null)).hasMessageContaining("JSON");
    }

    @Test
    void aJsonBodyForATextSchemaIsAJsonString() throws Exception {
        server.enqueue(OK);
        var body = new ApiOperation.Body(false, "application/json", JSON.readTree("{\"type\":\"string\"}"));
        var op = new ApiOperation("o", "PUT", "/j", "", List.of(), body);

        client(Credential.NONE).call(op, Map.of("body", "plain"), null);

        assertThat(sent().getBody().readUtf8()).isEqualTo("\"plain\"");
    }

    @Test
    void aGetThatIsGivenABodyIsRefused() throws Exception {
        var body = new ApiOperation.Body(false, "application/json", JSON.readTree("{}"));
        var op = new ApiOperation("o", "GET", "/g", "", List.of(), body);

        assertThatThrownBy(() -> client(Credential.NONE).call(op, Map.of("body", Map.of("a", 1)), null)).hasMessageContaining("don't take a body");
    }

    @Test
    void aPostWithNoBodyStillSendsAnEmptyOneAndADeleteSendsNone() throws Exception {
        server.enqueue(OK);
        server.enqueue(OK);

        client(Credential.NONE).call(new ApiOperation("o", "POST", "/p", "", List.of(), null), Map.of(), null);
        client(Credential.NONE).call(new ApiOperation("o", "DELETE", "/d", "", List.of(), null), Map.of(), null);

        RecordedRequest post = sent();
        assertThat(post.getMethod()).isEqualTo("POST");
        assertThat(post.getBodySize()).isZero();
        RecordedRequest delete = sent();
        assertThat(delete.getMethod()).isEqualTo("DELETE");
        assertThat(delete.getHeader("Content-Length")).isNull();
    }

    @Test
    void theJournalsKeyIsSentAsIdempotencyKeyOnlyForAnEffectThatAskedForIt() throws Exception {
        server.enqueue(OK);
        server.enqueue(OK);
        server.enqueue(OK);
        var post = new ApiOperation("o", "POST", "/p", "", List.of(), null);
        var get = new ApiOperation("o", "GET", "/g", "", List.of(), null);

        client(Credential.NONE, "idempotency", "true").call(post, Map.of(), "key-123");
        client(Credential.NONE, "idempotency", "true").call(get, Map.of(), "key-123");
        client(Credential.NONE).call(post, Map.of(), "key-123");

        assertThat(sent().getHeader("Idempotency-Key")).isEqualTo("key-123");
        assertThat(sent().getHeader("Idempotency-Key")).isNull();
        assertThat(sent().getHeader("Idempotency-Key")).isNull();
    }

    @Test
    void aHeaderValueWithALineBreakIsRefused() throws Exception {
        var op = new ApiOperation("o", "GET", "/g", "", List.of(p("X-Trace", "header", false, "string")), null);

        assertThatThrownBy(() -> client(Credential.NONE).call(op, Map.of("X-Trace", "a\r\nInjected: yes"), null)).hasMessageContaining("header value");
    }

    @Test
    void aMissingChallengeIsSaidSoInTheTypedFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        var op = new ApiOperation("o", "GET", "/g", "", List.of(), null);

        assertThatThrownBy(() -> client(new Credential("Authorization", null, "Bearer test-token-1234", null)).call(op, Map.of(), null))
                .isInstanceOf(CredentialRejected.class)
                .hasMessageContaining("no WWW-Authenticate challenge")
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("test-token-1234");
    }

    @Test
    void aRefusalWithNoCredentialSentIsJustAnAnswer() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setHeader("Content-Type", "text/plain").setBody("sign in"));
        var op = new ApiOperation("o", "GET", "/g", "", List.of(), null);

        assertThat(client(Credential.NONE).call(op, Map.of(), null)).startsWith("HTTP 401").contains("sign in");
    }

    @Test
    void aForbiddenThatIsNotAboutScopeIsAnOrdinaryAnswer() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403).setHeader("Content-Type", "text/plain").setBody("no").setHeader("WWW-Authenticate", "Bearer realm=x"));
        var op = new ApiOperation("o", "GET", "/g", "", List.of(), null);

        assertThat(client(new Credential("Authorization", null, "Bearer test-token-1234", null)).call(op, Map.of(), null)).startsWith("HTTP 403");
    }

    @Test
    void aMissingPathValueIsRefusedByName() throws Exception {
        var op = new ApiOperation("o", "GET", "/g/{id}", "", List.of(p("id", "path", true, "string")), null);

        assertThatThrownBy(() -> client(Credential.NONE).call(op, Map.of(), null)).isInstanceOf(ToolRefusal.class).hasMessageContaining("id is required");
    }

    @Test
    void aPlaceholderInsideASegmentIsFilledAndEncoded() throws Exception {
        server.enqueue(OK);
        var op = new ApiOperation("o", "GET", "/files/{name}.json", "", List.of(p("name", "path", true, "string")), null);

        client(Credential.NONE).call(op, Map.of("name", "my file"), null);

        assertThat(sent().getPath()).isEqualTo("/base/v1/files/my%20file.json");
    }

    @Test
    void aControlCharacterInAPathValueIsRefused() throws Exception {
        var op = new ApiOperation("o", "GET", "/g/{id}", "", List.of(p("id", "path", true, "string")), null);

        assertThatThrownBy(() -> client(Credential.NONE).call(op, Map.of("id", "a\u0001b"), null)).hasMessageContaining("control character");
        assertThat(Set.of(1)).isNotEmpty();
    }
}
