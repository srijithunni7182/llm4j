package io.github.llm4j.loom.tools;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenApiKindTest {

    @TempDir Path dir;

    MockWebServer api = new MockWebServer();
    final OpenApiKind kind = new OpenApiKind();

    @BeforeEach
    void start() throws Exception {
        api.start();
        Files.writeString(
                dir.resolve("refunds.json"),
                """
                {"openapi": "3.0.0", "info": {"title": "Refunds", "version": "1"}, "servers": [{"url": "%s"}],
                 "paths": {"/refunds": {"get": {"summary": "List", "responses": {"200": {"description": "ok"}}}}}}
                """
                        .formatted(api.url("/").toString().replaceAll("/$", "")));
    }

    @AfterEach
    void stop() throws Exception {
        api.shutdown();
    }

    private static Map<String, String> options(String... pairs) {
        Map<String, String> m = new HashMap<>();
        m.put("spec", "refunds.json");
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    private RecordedRequest call(Tool tool) throws Exception {
        api.enqueue(new MockResponse().setBody("[]"));
        assertThat(tool.execute(Map.of("endpoint", "/refunds", "method", "GET"))).isEqualTo("[]");
        return api.takeRequest();
    }

    @Test
    @Tag("CC-V2.10")
    void aFixedValueGoesInTheHeader() throws Exception {
        Tool tool =
                kind.create(
                        "Refunds", options("auth_header", "X-Key", "auth_value", "k-123456"), dir);

        assertThat(call(tool).getHeader("X-Key")).isEqualTo("k-123456");
    }

    @Test
    @Tag("CC-V2.10")
    void aFixedValueGoesInTheQuery() throws Exception {
        Tool tool =
                kind.create("Refunds", options("auth_query", "key", "auth_value", "k-123456"), dir);

        assertThat(call(tool).getRequestUrl().queryParameter("key")).isEqualTo("k-123456");
    }

    @Test
    @Tag("CC-V2.1")
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
    @Tag("CC-V2.2")
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
    @Tag("CC-V2.10")
    void withNoAuthNothingIsAdded() throws Exception {
        RecordedRequest request = call(kind.create("Refunds", options(), dir));

        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getRequestUrl().query()).isNull();
    }

    @Test
    @Tag("CC-V2.10")
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
    @Tag("CC-V1.6")
    void theKindSaysWhatItTakesAndWhatMayBeAskedFor() {
        assertThat(kind.name()).isEqualTo("openapi");
        assertThat(kind.required()).containsExactly("spec");
        assertThat(kind.optional())
                .containsExactlyInAnyOrder("auth_header", "auth_query", "auth_value");
        assertThat(kind.secrets()).containsExactly("auth_value");
        assertThat(kind.lateBound()).containsExactly("auth_value");
    }

    @Test
    @Tag("CC-V1.6")
    void theAuthOptionsMustBeConsistent() {
        assertThat(kind.check(options("auth_header", "H", "auth_value", "v"))).isNull();
        assertThat(kind.check(options("auth_query", "q", "auth_value", "v"))).isNull();
        assertThat(kind.check(options())).isNull();
        assertThat(kind.check(options("auth_header", "H", "auth_query", "q", "auth_value", "v")))
                .isEqualTo("use auth_header or auth_query, not both");
        assertThat(kind.check(options("auth_value", "v")))
                .isEqualTo("auth_value goes with exactly one of auth_header or auth_query");
        assertThat(kind.check(options("auth_header", "H")))
                .isEqualTo("auth_value goes with exactly one of auth_header or auth_query");
    }
}
