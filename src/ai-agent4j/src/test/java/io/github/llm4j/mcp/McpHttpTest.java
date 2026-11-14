package io.github.llm4j.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.mcp.testkit.McpConformance;
import io.github.llm4j.secret.CredentialSource;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A remote MCP server over Streamable HTTP, both eras (phase 36b; guards G74, G75, G81 to G83): the
 * probe picks the era and falls back, a version mismatch is answered from the server's list, JSON and
 * event-stream replies are read, a token is asked for each time and never put in the URL, and every
 * message either side sends validates against the official schema of its revision.
 */
class McpHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final McpConformance MODERN = new McpConformance("2026-07-28");
    private static final McpConformance LEGACY = new McpConformance("2025-11-25");

    MockWebServer server = new MockWebServer();
    final List<String> problems = Collections.synchronizedList(new ArrayList<>());
    final List<RecordedRequest> seen = Collections.synchronizedList(new ArrayList<>());
    volatile boolean legacyServer;
    volatile boolean stream;
    volatile String requiredToken;
    volatile int rejectFirst;
    volatile boolean askUnsupportedOnce;

    @BeforeEach
    void start() throws Exception {
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest r) {
                        seen.add(r);
                        return answer(r);
                    }
                });
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private MockResponse answer(RecordedRequest r) {
        try {
            String auth = r.getHeader("Authorization");
            if (requiredToken != null && !("Bearer " + requiredToken).equals(auth))
                return new MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer resource_metadata=\"https://auth.example.org/m\"");
            String text = r.getBody().readUtf8();
            JsonNode req = JSON.readTree(text);
            boolean modernRequest = req.path("params").path("_meta").has("io.modelcontextprotocol/protocolVersion");
            problems.addAll(check(req, modernRequest));
            String method = req.path("method").asText();
            if (legacyServer && modernRequest) return new MockResponse().setResponseCode(400).setBody("{\"error\":\"unknown\"}");
            if (!req.has("id")) return new MockResponse().setResponseCode(202);
            if (askUnsupportedOnce && modernRequest) {
                askUnsupportedOnce = false;
                return new MockResponse().setResponseCode(400).setBody("{\"jsonrpc\":\"2.0\",\"id\":" + req.get("id") + ",\"error\":{\"code\":-32022,\"message\":\"unsupported\",\"data\":{\"requested\":\"x\",\"supported\":[\"2026-07-28\"]}}}");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            switch (method) {
                case "server/discover" -> {
                    result.put("supportedVersions", List.of("2026-07-28"));
                    result.put("capabilities", Map.of("tools", Map.of()));
                    result.put("resultType", "complete");
                    result.put("cacheScope", "private");
                    result.put("ttlMs", 0);
                }
                case "initialize" -> {
                    result.put("protocolVersion", "2025-11-25");
                    result.put("capabilities", Map.of("tools", Map.of()));
                    result.put("serverInfo", Map.of("name", "fake", "version", "1"));
                }
                case "tools/list" -> {
                    result.put("tools", List.of(Map.of("name", "echo", "description", "d", "inputSchema", Map.of("type", "object"))));
                    if (modernRequest) {
                        result.put("resultType", "complete");
                        result.put("cacheScope", "private");
                        result.put("ttlMs", 0);
                    }
                }
                default -> {
                    result.put("content", List.of(Map.of("type", "text", "text", "echo: " + req.path("params").path("arguments").path("message").asText())));
                    if (modernRequest) result.put("resultType", "complete");
                }
            }
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("jsonrpc", "2.0");
            reply.put("id", JSON.treeToValue(req.get("id"), Object.class));
            reply.put("result", result);
            String body = JSON.writeValueAsString(reply);
            problems.addAll(checkReply(body, method, modernRequest));
            MockResponse out = new MockResponse();
            if (method.equals("initialize")) out.setHeader("Mcp-Session-Id", "session-123");
            if (stream && method.equals("tools/call"))
                return out.setHeader("Content-Type", "text/event-stream").setBody(": keep-alive\n\nevent: message\ndata: " + body.substring(0, body.indexOf(',') + 1) + "\ndata: " + body.substring(body.indexOf(',') + 1) + "\n\n");
            return out.setHeader("Content-Type", "application/json").setBody(body);
        } catch (IOException e) {
            return new MockResponse().setResponseCode(500);
        }
    }

    private static List<String> check(JsonNode req, boolean modernRequest) {
        return (modernRequest ? MODERN : LEGACY).problems(req.toString(), null);
    }

    private static List<String> checkReply(String body, String method, boolean modernRequest) {
        return (modernRequest ? MODERN : LEGACY).problems(body, method);
    }

    private McpHttpPoster poster() {
        OkHttpClient client = new OkHttpClient();
        return (url, headers, body) -> {
            Request.Builder b = new Request.Builder().url(url).post(RequestBody.create(body, MediaType.get("application/json")));
            headers.forEach(b::header);
            try (Response r = client.newCall(b.build()).execute()) {
                Map<String, String> h = new LinkedHashMap<>();
                r.headers().names().forEach(n -> h.put(n.toLowerCase(), r.header(n)));
                return new McpHttpPoster.Reply(r.code(), h, r.body() == null ? new byte[0] : r.body().bytes());
            }
        };
    }

    private McpClient client(CredentialSource token) {
        return new McpClient(new McpHttpTransport(server.url("/mcp").toString(), poster(), token), Duration.ofSeconds(10));
    }

    @Test
    void aModernServerIsProbedAndEveryMessageConformsToTheOfficialSchema() throws Exception {
        McpClient client = client(null);
        client.initialize();

        assertThat(client.isModern()).isTrue();
        assertThat(client.listTools()).extracting(t -> t.get("name")).containsExactly("echo");
        client.callTool("echo", Map.of("message", "hi"));

        assertThat(problems).isEmpty();
        RecordedRequest call = seen.get(seen.size() - 1);
        assertThat(call.getHeader("MCP-Protocol-Version")).isEqualTo("2026-07-28");
        assertThat(call.getHeader("Mcp-Method")).isEqualTo("tools/call");
        assertThat(call.getHeader("Mcp-Name")).isEqualTo("echo");
        assertThat(call.getHeader("Accept")).contains("application/json").contains("text/event-stream");
        assertThat(call.getPath()).isEqualTo("/mcp");
        assertThat(seen).noneMatch(r -> r.getRequestUrl().toString().contains("session"));
    }

    @Test
    void aLegacyServerThatRejectsTheModernRequestIsReachedByTheHandshakeWithItsSession() throws Exception {
        legacyServer = true;
        McpClient client = client(null);
        client.initialize();

        assertThat(client.isModern()).isFalse();
        client.listTools();

        assertThat(problems).isEmpty();
        RecordedRequest list = seen.get(seen.size() - 1);
        assertThat(list.getHeader("Mcp-Session-Id")).isEqualTo("session-123");
        assertThat(list.getHeader("MCP-Protocol-Version")).isEqualTo("2025-11-25");
    }

    @Test
    void anUnsupportedVersionErrorIsAnsweredFromTheServersList() throws Exception {
        askUnsupportedOnce = true;
        McpClient client = client(null);
        client.initialize();

        assertThat(client.isModern()).isTrue();
        assertThat(client.negotiatedVersion()).isEqualTo("2026-07-28");
    }

    @Test
    void anEventStreamReplyWithCommentsAndSplitDataIsRead() throws Exception {
        stream = true;
        McpClient client = client(null);
        client.initialize();

        Map<?, ?> result = (Map<?, ?>) client.callTool("echo", Map.of("message", "streamed"));

        assertThat(String.valueOf(result.get("content"))).contains("echo: streamed");
    }

    @Test
    void theEventsOfAStreamAreItsDataLines() {
        assertThat(McpHttpTransport.events(": hi\n\nid: 1\ndata: {\"a\":\ndata: 1}\n\ndata: {\"b\":2}\n"))
                .containsExactly("{\"a\":\n1}", "{\"b\":2}");
    }

    private static final class Tokens implements CredentialSource {
        final List<String> asked = new ArrayList<>();
        int rejected;
        final String[] values;

        Tokens(String... values) {
            this.values = values;
        }

        @Override
        public String get() {
            String v = values[Math.min(asked.size(), values.length - 1)];
            asked.add(v);
            return v;
        }

        @Override
        public void rejected() {
            rejected++;
        }
    }

    @Test
    void aTokenIsAskedForOnEveryRequestAndNeverInTheUrl() throws Exception {
        requiredToken = "test-token-777";
        Tokens tokens = new Tokens("test-token-777");
        McpClient client = client(tokens);
        client.initialize();
        client.listTools();

        assertThat(tokens.asked).hasSizeGreaterThanOrEqualTo(2);
        assertThat(seen).allSatisfy(r -> {
            assertThat(r.getHeader("Authorization")).isEqualTo("Bearer test-token-777");
            assertThat(r.getRequestUrl().toString()).doesNotContain("test-token-777");
        });
    }

    @Test
    void aRefusalIsRetriedOnceWithAFreshTokenThenTypedAndRedacted() throws Exception {
        requiredToken = "test-fresh-222";
        Tokens stale = new Tokens("test-stale-111", "test-fresh-222");
        McpClient ok = client(stale);
        ok.initialize();
        assertThat(stale.rejected).isEqualTo(1);

        Tokens never = new Tokens("test-wrong-333");
        McpClient rejected = client(never);
        assertThatThrownBy(rejected::initialize)
                .isInstanceOf(McpCredentialRejectedException.class)
                .hasMessageStartingWith("credential_rejected:")
                .hasMessageContaining("resource_metadata")
                .hasMessageNotContaining("test-wrong-333");
    }

    @Test
    void connectionsWithDifferentTokensNeverCrossTokens() throws Exception {
        List<String> a = Collections.synchronizedList(new ArrayList<>());
        List<String> b = Collections.synchronizedList(new ArrayList<>());
        McpClient one = client(() -> { a.add("Bearer test-aaaa-1111"); return "Bearer test-aaaa-1111"; });
        McpClient two = client(() -> { b.add("Bearer test-bbbb-2222"); return "Bearer test-bbbb-2222"; });
        one.initialize();
        two.initialize();
        one.listTools();
        two.listTools();

        long oneRequests = seen.stream().filter(r -> "Bearer test-aaaa-1111".equals(r.getHeader("Authorization"))).count();
        long twoRequests = seen.stream().filter(r -> "Bearer test-bbbb-2222".equals(r.getHeader("Authorization"))).count();
        assertThat(oneRequests).isEqualTo(a.size());
        assertThat(twoRequests).isEqualTo(b.size());
        assertThat(oneRequests + twoRequests).isEqualTo(seen.size());
    }

    @Test
    void aToolThatAsksForMoreInputFailsWithAClearMessage() throws Exception {
        McpClient client = client(null);
        client.initialize();
        // an input_required result, as the multi-round-trip pattern defines it
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest r) {
                        String id = r.getBody().readUtf8().replaceAll(".*\"id\":(\\d+).*", "$1");
                        return new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"resultType\":\"input_required\",\"content\":[]}}");
                    }
                });

        assertThatThrownBy(() -> client.callTool("echo", Map.of())).hasMessageContaining("input_required").hasMessageContaining("does not support");
    }

    @Test
    void anHttpErrorThatIsNotAuthIsReportedWithItsStatus() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                return new MockResponse().setResponseCode(500).setBody("boom");
            }
        });
        McpClient client = client(null);

        assertThatThrownBy(client::initialize).isInstanceOf(McpHttpException.class).hasMessageContaining("500");
    }
}
