package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.inspect.control.AuditSink;
import io.github.llm4j.loom.inspect.control.LoopActions;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** A real server with a fake run and fake "create test" actions, for the Fabric capture tests. */
abstract class LoopRouteFixture {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String TRACE = Ids.traceId("r-1");
    static final String CASE = "captured:\n  status: observed\ncases: []\n";

    final HttpClient http = HttpClient.newHttpClient();
    final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    final List<Map<String, Object>> audit = Collections.synchronizedList(new ArrayList<>());
    volatile boolean allowed = true;
    volatile LoopActions.Outcome answer =
            new LoopActions.Outcome(true, 200, "Captured.", "a.test.yaml", CASE, null, null);
    InspectServer server;
    String base;
    final String password = "correct horse battery";

    final LoopActions actions =
            new LoopActions() {
                @Override
                public boolean enabled() {
                    return allowed;
                }

                @Override
                public String disabledReason() {
                    return "start Fabric with --allow-capture";
                }

                @Override
                public Outcome capture(String traceId, String kind, boolean injection, String who) {
                    asked.add("capture " + traceId + " " + kind + " " + injection + " " + who);
                    return answer;
                }

                @Override
                public Outcome tryChange(
                        String traceId,
                        String at,
                        Map<String, String> sets,
                        Map<String, String> answers,
                        String who) {
                    asked.add("whatif " + traceId + " " + at + " " + sets + " " + answers);
                    return new Outcome(true, 200, "Replayed.", null, "same result", null, null);
                }

                @Override
                public int testsFor(String kind) {
                    return kind.equals("looping") ? 3 : 0;
                }
            };

    @BeforeEach
    void start() {
        RunRegistry registry = new RunRegistry();
        registry.applyLogs(
                Resource.of("local", "i_1"),
                List.of(
                        new LogRec(
                                1_000_000_000L,
                                LogRec.INFO,
                                "w",
                                Map.of(
                                        Semconv.EVENT_NAME,
                                        Semconv.EV_RUN_START,
                                        Semconv.LOOM_SEQ,
                                        1L,
                                        Semconv.LOOM_RUN_ID,
                                        "r-1",
                                        Semconv.LOOM_WORKFLOW,
                                        "Main"),
                                TRACE,
                                null)));
        AdminSessions sessions = new AdminSessions(() -> password, Clock.systemUTC(), false);
        server =
                new InspectServer(
                        registry,
                        InspectServer.config()
                                .bind("127.0.0.1", 0)
                                .sessions(sessions)
                                .loop(actions, (AuditSink) audit::add));
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    HttpResponse<String> send(
            String method, String path, String body, String cookie, String csrf, String type)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (type != null) b.header("Content-Type", type);
        if (cookie != null) b.header("Cookie", cookie);
        if (csrf != null) b.header("X-CSRF-Token", csrf);
        b.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    String[] signIn() throws Exception {
        HttpResponse<String> r =
                send(
                        "POST",
                        "/api/login",
                        "{\"password\":\"" + password + "\"}",
                        null,
                        null,
                        "application/json");
        assertThat(r.statusCode()).isEqualTo(200);
        return new String[] {
            r.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],
            JSON.readTree(r.body()).get("csrf").asText()
        };
    }

    HttpResponse<String> post(String action, String body, String[] session) throws Exception {
        return send(
                "POST",
                "/api/runs/" + TRACE + "/" + action,
                body,
                session == null ? null : session[0],
                session == null ? null : session[1],
                "application/json");
    }
}
