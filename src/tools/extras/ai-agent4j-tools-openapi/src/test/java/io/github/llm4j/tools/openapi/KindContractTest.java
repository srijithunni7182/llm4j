package io.github.llm4j.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.secret.CredentialSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a script written before phase 36 relied on (G92), carried over from the Loom-side kind this
 * one replaced: a key in a header or the query, a credential asked for on every request, a
 * description found beside the script or by absolute path, and the options the kind takes.
 */
class KindContractTest {

    @TempDir Path dir;

    MockWebServer api = new MockWebServer();
    final OpenApiKind kind = new OpenApiKind();

    @BeforeEach
    void start() throws Exception {
        api.start();
        Files.writeString(
                dir.resolve("refunds.json"),
                """
                {"openapi": "3.0.0", "info": {"title": "Refunds", "version": "1"},
                 "paths": {"/refunds": {"get": {"summary": "List", "responses": {"200": {"description": "ok"}}}}}}
                """);
    }

    @AfterEach
    void stop() throws Exception {
        api.shutdown();
    }

    private Map<String, String> options(String... pairs) {
        Map<String, String> m = new HashMap<>();
        m.put("spec", "refunds.json");
        // the address the declaration names is the author's choice, so a local server is reachable
        m.put("base_url", api.url("/").toString().replaceAll("/$", ""));
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    private RecordedRequest call(Tool tool) throws Exception {
        api.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("[]"));
        assertThat(tool.execute(Map.of("endpoint", "/refunds", "method", "GET")))
                .contains("HTTP 200")
                .endsWith("[]");
        return api.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void aFixedValueGoesInTheHeader() throws Exception {
        Tool tool =
                kind.create("Refunds", options("auth_header", "X-Key", "auth_value", "k-123456"), dir);

        assertThat(call(tool).getHeader("X-Key")).isEqualTo("k-123456");
    }

    @Test
    void aFixedValueGoesInTheQuery() throws Exception {
        Tool tool =
                kind.create("Refunds", options("auth_query", "key", "auth_value", "k-123456"), dir);

        assertThat(call(tool).getRequestUrl().queryParameter("key")).isEqualTo("k-123456");
    }

    @Test
    void aCredentialToAskForGoesInTheHeaderEachTime() throws Exception {
        int[] n = {0};
        CredentialSource source = () -> "Bearer t" + (++n[0]);
        Tool tool =
                kind.create(
                        "Refunds",
                        options("auth_header", "Authorization"),
                        Map.of("auth_value", source),
                        dir,
                        EffectContext.noop());

        assertThat(call(tool).getHeader("Authorization")).isEqualTo("Bearer t1");
        assertThat(call(tool).getHeader("Authorization")).isEqualTo("Bearer t2");
    }

    @Test
    void aCredentialToAskForGoesInTheQueryEachTime() throws Exception {
        Tool tool =
                kind.create(
                        "Refunds",
                        options("auth_query", "access_token"),
                        Map.of("auth_value", CredentialSource.of("t1")),
                        dir,
                        EffectContext.noop());

        assertThat(call(tool).getRequestUrl().queryParameter("access_token")).isEqualTo("t1");
    }

    @Test
    void withNoAuthNothingIsAdded() throws Exception {
        RecordedRequest request = call(kind.create("Refunds", options(), dir));

        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getRequestUrl().query()).isNull();
    }

    @Test
    void theSpecIsFoundBesideTheScriptOrByAbsolutePath() throws Exception {
        Path elsewhere = Files.createTempDirectory(dir, "other");

        assertThat(call(kind.create("Refunds", options(), dir)).getPath()).isEqualTo("/refunds");
        assertThat(
                        call(kind.create(
                                        "Refunds",
                                        options("spec", dir.resolve("refunds.json").toString()),
                                        elsewhere))
                                .getPath())
                .isEqualTo("/refunds");
    }

    @Test
    void theKindSaysWhatItTakesAndWhatMayBeAskedFor() {
        assertThat(kind.name()).isEqualTo("openapi");
        assertThat(kind.required()).containsExactly("spec");
        assertThat(kind.optional())
                .contains(
                        "operation", "base_url", "server", "max_bytes", "save_dir", "save_max_bytes",
                        "auth_header", "auth_query", "auth_value", "pin", "timeout", "retries", "idempotency",
                        "on_unknown", "hosts", "allow_http", "allow_private", "follow_redirects");
        assertThat(kind.secrets()).containsExactly("auth_value");
        assertThat(kind.lateBound()).containsExactly("auth_value");
        assertThat(kind.buildsAtRunStart()).isTrue();
    }

    @Test
    void theAuthOptionsMustBeConsistent() {
        assertThat(kind.check(options("auth_header", "H", "auth_value", "value-1234"), dir)).isNull();
        assertThat(kind.check(options("auth_query", "q", "auth_value", "value-1234"), dir)).isNull();
        assertThat(kind.check(options(), dir)).isNull();
        assertThat(kind.check(options("auth_header", "H", "auth_query", "q", "auth_value", "value-1234"), dir))
                .contains("auth_header or auth_query, not both");
        assertThat(kind.check(options("auth_value", "value-1234"), dir))
                .contains("auth_value goes with exactly one of auth_header or auth_query");
        assertThat(kind.check(options("auth_header", "H"), dir))
                .contains("auth_value goes with exactly one of auth_header or auth_query");
    }

    @Test
    void aPinNeedsAnOperationAndTheRightShape() {
        assertThat(kind.check(options("pin", "sha256-0123456789abcdef"), dir)).contains("operation:");
        assertThat(kind.check(options("operation", "x", "pin", "abc"), dir)).contains("sha256-");
        assertThat(kind.check(options("operation", "x", "pin", "sha256-0123456789abcdef"), dir)).isNull();
    }

    @Test
    void anOperationNameMustBeAnIdentifier() {
        assertThat(kind.check(options("operation", "bad name"), dir)).contains("operationId");
    }

    @Test
    void aBadBaseUrlOrServerIsACheckError() {
        assertThat(kind.check(options("base_url", "not a url"), dir)).contains("base_url");
        assertThat(kind.check(options("base_url", "https://a.example/x?y=1"), dir)).contains("query");
        assertThat(kind.check(options("server", "nope"), dir)).contains("server");
        assertThat(kind.check(options("base_url", "http://api.example.invalid"), dir)).contains("https");
    }

    @Test
    void theSizeOptionsAreBounded() {
        assertThat(kind.check(options("max_bytes", "100m"), dir)).contains("max_bytes");
        assertThat(kind.check(options("save_dir", "out", "save_max_bytes", "1"), dir))
                .contains("save_max_bytes");
    }
}
