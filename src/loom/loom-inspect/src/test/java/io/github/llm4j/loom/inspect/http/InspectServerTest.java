package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The embedded page's server, on a real loopback socket. */
class InspectServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRACE = Ids.traceId("r-1");

    private final HttpClient http = HttpClient.newHttpClient();
    private RunRegistry registry;
    private InspectServer server;
    private String base;

    @BeforeEach
    void start() {
        registry = new RunRegistry();
        registry.applyLogs(Resource.of("local", "i_1"), List.of(new LogRec(1_000_000_000L, LogRec.INFO, "workflow Main",
                Map.of(Semconv.EVENT_NAME, Semconv.EV_RUN_START, Semconv.LOOM_SEQ, 1L, Semconv.LOOM_RUN_ID, "r-1", Semconv.LOOM_WORKFLOW, "Main"), TRACE, null)));
        server = new InspectServer(registry, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthListAndRunViewAreJson() throws Exception {
        assertThat(JSON.readTree(get("/api/health").body()).get("status").asText()).isEqualTo("ok");
        JsonNode runs = JSON.readTree(get("/api/runs").body()).get("runs");
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("workflow").asText()).isEqualTo("Main");
        HttpResponse<String> run = get("/api/runs/" + TRACE);
        assertThat(run.statusCode()).isEqualTo(200);
        assertThat(run.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
        assertThat(JSON.readTree(run.body()).get("status").asText()).isEqualTo("running");
    }

    @Test
    void unknownOrMalformedRunsAndPathsAre404() throws Exception {
        assertThat(get("/api/runs/" + "0".repeat(32)).statusCode()).isEqualTo(404);
        assertThat(get("/api/runs/not-a-trace-id").statusCode()).isEqualTo(404);
        assertThat(get("/api/runs/../../etc/passwd").statusCode()).isEqualTo(404);
        assertThat(get("/nope").statusCode()).isEqualTo(404);
    }

    @Test
    void thePageAndItsAssetsAreServedWithStrictHeaders() throws Exception {
        HttpResponse<String> page = get("/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<title>loom-inspect</title>").contains("/app.js");
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(c -> assertThat(c).contains("default-src 'self'").contains("frame-ancestors 'none'"));
        assertThat(page.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(page.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(get("/app.js").headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("text/javascript"));
        assertThat(get("/app.css").body()).contains("--live");
        HttpResponse<byte[]> logo = http.send(HttpRequest.newBuilder(URI.create(base + "/logo.png")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(logo.body()[1]).isEqualTo((byte) 'P'); // a PNG
    }

    @Test
    void theScriptNeverBuildsHtmlFromRunText() throws Exception {
        String js = get("/app.js").body();
        assertThat(js).doesNotContain("innerHTML").doesNotContain("insertAdjacentHTML").doesNotContain("document.write").doesNotContain("eval(");
    }

    @Test
    void itIsReadOnly() throws Exception {
        HttpResponse<String> post = http.send(HttpRequest.newBuilder(URI.create(base + "/api/runs")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(405);
        assertThat(post.headers().firstValue("Allow")).hasValue("GET, HEAD");
        HttpResponse<String> delete = http.send(HttpRequest.newBuilder(URI.create(base + "/api/runs/" + TRACE)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(delete.statusCode()).isEqualTo(405);
    }

    @Test
    void aRequestForAnotherHostNameIsRefusedSoARebindingPageCannotReadIt() throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.getOutputStream().write("GET /api/runs HTTP/1.1\r\nHost: evil.example.com\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            String head = new String(in.readNBytes(32), StandardCharsets.US_ASCII);
            assertThat(head).startsWith("HTTP/1.1 403");
        }
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.getOutputStream().write(("GET /api/health HTTP/1.1\r\nHost: localhost:" + server.port() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            assertThat(new String(s.getInputStream().readNBytes(32), StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 200");
        }
    }

    @Test
    void itWillNotListenOnAnAddressOtherThanLoopback() {
        assertThatThrownBy(() -> new InspectServer(registry, "0.0.0.0", 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no login").hasMessageContaining("127.0.0.1");
    }
}
