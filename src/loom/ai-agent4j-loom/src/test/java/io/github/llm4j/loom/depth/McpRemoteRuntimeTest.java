package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.secret.InvokerCredentials;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A remote MCP server in a script (phase 36b, D6 and D8): the engine lists its tools, and each run's own token is sent on that run's calls. */
class McpRemoteRuntimeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    /** A modern-era server that records the token of each request. */
    private static MockWebServer server(List<String> tokens) throws Exception {
        MockWebServer s = new MockWebServer();
        s.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                try {
                    tokens.add(String.valueOf(r.getHeader("Authorization")));
                    JsonNode req = JSON.readTree(r.getBody().readUtf8());
                    String method = req.path("method").asText();
                    String id = req.get("id").toString();
                    String result =
                            switch (method) {
                                case "server/discover" -> "{\"supportedVersions\":[\"2026-07-28\"],\"capabilities\":{},\"resultType\":\"complete\",\"cacheScope\":\"private\",\"ttlMs\":0}";
                                case "tools/list" -> "{\"tools\":[{\"name\":\"echo\",\"inputSchema\":{\"type\":\"object\"}}],\"resultType\":\"complete\",\"cacheScope\":\"private\",\"ttlMs\":0}";
                                default -> "{\"content\":[{\"type\":\"text\",\"text\":\"echo: " + req.path("params").path("arguments").path("message").asText() + "\"}],\"resultType\":\"complete\"}";
                            };
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
        });
        s.start();
        return s;
    }

    private String script(MockWebServer s, String options) {
        return "mcp R { transport: \"http\" url: \"http://localhost:" + s.getPort() + "/mcp\" " + options + " effects: { echo: reads } }\n"
                + "agent A { model: \"m\" system: \"s\" mcp_servers: [R] }\nworkflow Main() { delegate \"go\" to A -> r }\n";
    }

    @Test
    void theToolsAreListedWithTheListCredentialAndACallCarriesTheRunsOwnToken() throws Exception {
        List<String> tokens = Collections.synchronizedList(new ArrayList<>());
        try (MockWebServer s = server(tokens)) {
            Harness h = new Harness(dir);
            h.env.put("LIST_TOKEN", "Bearer test-list-0000");
            h.answers(Harness.call("echo", "{\"message\": \"hi\"}"), Harness.done("ok"));
            HarnessExecutor e = h.executor(script(s, "auth: invoker.token list_auth: env.LIST_TOKEN"), null);
            e.initialize();
            assertThat(tokens).isNotEmpty().allMatch("Bearer test-list-0000"::equals);
            tokens.clear();

            e.executeWorkflow("Main", Map.of(), InvokerCredentials.of(Map.of("token", "Bearer test-run-one-1111")));

            assertThat(tokens).isNotEmpty().allMatch("Bearer test-run-one-1111"::equals);
            assertThat(h.seen()).contains("echo: hi");
            e.shutdown();
        }
    }

    @Test
    void aStaticTokenServesBothListingAndCalls() throws Exception {
        List<String> tokens = Collections.synchronizedList(new ArrayList<>());
        try (MockWebServer s = server(tokens)) {
            Harness h = new Harness(dir);
            h.env.put("SRV_TOKEN", "Bearer test-static-2222");
            HarnessExecutor e = h.executor(script(s, "auth: env.SRV_TOKEN"), null);
            e.initialize();

            assertThat(tokens).isNotEmpty().allMatch("Bearer test-static-2222"::equals);
            e.shutdown();
        }
    }

    @Test
    void anInvokerTokenWithNothingToListWithIsALoadErrorThatSaysWhy() throws Exception {
        try (MockWebServer s = server(new ArrayList<>())) {
            Harness h = new Harness(dir);

            assertThatThrownBy(() -> h.ready(script(s, "auth: invoker.token")))
                    .isInstanceOf(LoomLoadException.class)
                    .hasMessageContaining("list_auth");
        }
    }

    @Test
    void aLiteralTokenOrAProgramOptionOnARemoteServerIsAnError() throws Exception {
        try (MockWebServer s = server(new ArrayList<>())) {
            Harness h = new Harness(dir);
            assertThatThrownBy(() -> h.ready(script(s, "auth: \"literal-token-1234\""))).hasMessageContaining("never a literal");
            assertThatThrownBy(() -> h.ready(script(s, "isolation: process"))).hasMessageContaining("are for a program");
        }
    }

    @Test
    void aRemoteServerNeedsAnAddressAndAProgramMayNotHaveOne() {
        Harness h = new Harness(dir);
        assertThatThrownBy(() -> h.ready("mcp R { transport: \"http\" }\nagent A { model: \"m\" system: \"s\" mcp_servers: [R] }\nworkflow Main() { delegate \"x\" to A -> r }"))
                .hasMessageContaining("needs url");
        assertThatThrownBy(() -> h.ready("mcp R { cmd: \"srv\" isolation: none url: \"https://a.example\" }\nagent A { model: \"m\" system: \"s\" mcp_servers: [R] }\nworkflow Main() { delegate \"x\" to A -> r }"))
                .hasMessageContaining("are for a remote server");
    }

    @Test
    void aRemoteServerWithNoAuthIsReachedWithNoCredentialAtAll() throws Exception {
        List<String> tokens = Collections.synchronizedList(new ArrayList<>());
        try (MockWebServer s = server(tokens)) {
            Harness h = new Harness(dir);
            HarnessExecutor e = h.executor(script(s, ""), null);

            e.initialize();

            assertThat(tokens).isNotEmpty().allMatch("null"::equals);
            e.shutdown();
        }
    }
}
