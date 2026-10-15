package io.github.llm4j.loom.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R2, R3, R6 at Fabric: the fleet routes answer the same as the embedded page's, behind the sign-in, from what processes reported. */
class FabricFleetRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private FabricServer server;
    private String base;

    @BeforeEach
    void start() {
        Vault vault = Vault.create(dir.resolve("vault.json"), "a-long-enough-master-key");
        vault.put("client.alpha", Vault.newClientKey());
        vault.put(Vault.ADMIN, "correct horse battery");
        server = new FabricServer(new FabricServer.Settings(dir, "127.0.0.1", 0, null, false, 30, 2L << 30), vault, new PrintStream(new ByteArrayOutputStream()), 100, 200);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String login() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"correct horse battery\"}")).build(), HttpResponse.BodyHandlers.ofString());
        return r.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private HttpResponse<String> api(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (cookie != null) b.header("Cookie", cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void tool(String run, String agent, String tool, String reach, String category, long endNanos) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.GEN_AI_TOOL_NAME, tool);
        a.put(Semconv.LOOM_TOOL_REACH, reach);
        a.put(Semconv.LOOM_TOOL_REACH_KNOWN, true);
        if (category != null) a.put(Semconv.ERROR_TYPE, category);
        server.registry().applySpans(Resource.of("alpha", "i_1"), List.of(new SpanRec(Ids.traceId(run), Ids.spanId(run, tool + endNanos), null, tool, SpanRec.CLIENT, endNanos - 1000, endNanos, a,
                category == null ? SpanRec.STATUS_OK : SpanRec.STATUS_ERROR, null, List.of())));
    }

    @Test
    void theFleetRoutesNeedTheSignIn() throws Exception {
        assertThat(api("/api/tools", null).statusCode()).isEqualTo(401);
        assertThat(api("/api/security", null).statusCode()).isEqualTo(401);
    }

    @Test
    void whatProcessesReportedIsRolledUpForTheAdministrator() throws Exception {
        for (int i = 1; i <= 4; i++) tool("r1", "Writer", "Search", "untrusted", "timeout", 1_000_000_000L * i);
        tool("r1", "Writer", "DB", "private", null, 5_000_000_000L);
        tool("r1", "Writer", "Send", "outward", null, 6_000_000_000L);
        String cookie = login();
        JsonNode tools = JSON.readTree(api("/api/tools", cookie).body());
        assertThat(tools.get("rows").get(0).get("name").asText()).isEqualTo("Search");
        assertThat(tools.get("rows").get(0).get("health").get("state").asText()).isEqualTo("failing");
        assertThat(tools.get("counts").get("failing").asInt()).isEqualTo(1);
        JsonNode security = JSON.readTree(api("/api/security?client=alpha", cookie).body());
        assertThat(security.get("agents").get(0).get("verdict").asText()).isEqualTo("exercised");
        assertThat(JSON.readTree(api("/api/runs", cookie).body()).get("runs").get(0).get("security").asInt()).isEqualTo(1);
    }
}
