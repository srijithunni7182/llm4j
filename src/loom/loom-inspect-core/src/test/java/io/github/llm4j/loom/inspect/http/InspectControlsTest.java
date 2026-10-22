package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.inspect.control.AuditSink;
import io.github.llm4j.loom.inspect.control.Controls;
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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who may pause, resume, suspend and stop a run from the page, and what is recorded when they do. A
 * fake run, a real server.
 */
class InspectControlsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRACE = Ids.traceId("r-1");

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> audit = Collections.synchronizedList(new ArrayList<>());
    private volatile String password = "correct horse battery";
    private volatile boolean enabled = true;
    private InspectServer server;
    private String base;

    private final Controls controls =
            new Controls() {
                @Override
                public boolean enabled() {
                    return enabled;
                }

                @Override
                public String disabledReason() {
                    return "set LOOM_INSPECT_ADMIN_PASSWORD to enable";
                }

                @Override
                public Set<Action> allowed(String traceId) {
                    return Set.of(Action.PAUSE, Action.STOP);
                }

                @Override
                public Outcome request(String traceId, Action action, String who) {
                    asked.add(action.word() + " " + traceId + " by " + who);
                    return action == Action.STOP
                            ? Outcome.taken("stop will take effect at the next step")
                            : Outcome.refused(409, "not now");
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
                                .controls(controls, (AuditSink) audit::add));
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> send(
            String method, String path, String body, String cookie, String csrf, String origin)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (body != null) b.header("Content-Type", "application/json");
        if (cookie != null) b.header("Cookie", cookie);
        if (csrf != null) b.header("X-CSRF-Token", csrf);
        b.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        HttpRequest r = b.build();
        // java.net.http will not set Origin through a header, so a cross-site request is made with
        // a raw socket in its own test below
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private String[] signIn() throws Exception {
        HttpResponse<String> r =
                send("POST", "/api/login", "{\"password\":\"" + password + "\"}", null, null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        return new String[] {
            r.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],
            JSON.readTree(r.body()).get("csrf").asText()
        };
    }

    @Test
    void theSessionRouteSaysWhetherControlsAreOnAndWhoIsSignedIn() throws Exception {
        JsonNode anon = JSON.readTree(send("GET", "/api/session", null, null, null, null).body());
        assertThat(anon.get("signedIn").asBoolean()).isFalse();
        assertThat(anon.get("controlsEnabled").asBoolean()).isTrue();
        assertThat(anon.has("csrf")).isFalse();
        String[] s = signIn();
        JsonNode in = JSON.readTree(send("GET", "/api/session", null, s[0], null, null).body());
        assertThat(in.get("signedIn").asBoolean()).isTrue();
        assertThat(in.get("csrf").asText()).isEqualTo(s[1]);
    }

    @Test
    void aRunsViewListsWhatMayBeAskedOfItOnlyToASignedInAdministrator() throws Exception {
        JsonNode anon =
                JSON.readTree(send("GET", "/api/runs/" + TRACE, null, null, null, null).body());
        assertThat(anon.at("/controls/signedIn").asBoolean()).isFalse();
        assertThat(anon.at("/controls/allowed")).isEmpty();
        String[] s = signIn();
        JsonNode in =
                JSON.readTree(send("GET", "/api/runs/" + TRACE, null, s[0], null, null).body());
        assertThat(in.at("/controls/allowed").toString()).isEqualTo("[\"pause\",\"stop\"]");
    }

    @Test
    void aControlNeedsASession_theTokenItGave_andARunThatExists() throws Exception {
        assertThat(
                        send("POST", "/api/runs/" + TRACE + "/stop", "{}", null, null, null)
                                .statusCode())
                .isEqualTo(401);
        String[] s = signIn();
        assertThat(
                        send("POST", "/api/runs/" + TRACE + "/stop", "{}", s[0], null, null)
                                .statusCode())
                .isEqualTo(403); // no token
        assertThat(
                        send("POST", "/api/runs/" + TRACE + "/stop", "{}", s[0], "guess", null)
                                .statusCode())
                .isEqualTo(403); // wrong token
        assertThat(
                        send(
                                        "POST",
                                        "/api/runs/" + "0".repeat(32) + "/stop",
                                        "{}",
                                        s[0],
                                        s[1],
                                        null)
                                .statusCode())
                .isEqualTo(404); // no such run
        assertThat(
                        send("POST", "/api/runs/" + TRACE + "/explode", "{}", s[0], s[1], null)
                                .statusCode())
                .isEqualTo(404); // no such action
        assertThat(asked).isEmpty();
        HttpResponse<String> ok =
                send("POST", "/api/runs/" + TRACE + "/stop", "{}", s[0], s[1], null);
        assertThat(ok.statusCode()).isEqualTo(202);
        assertThat(JSON.readTree(ok.body()).get("message").asText()).contains("next step");
        assertThat(asked).containsExactly("stop " + TRACE + " by admin@127.0.0.1");
    }

    @Test
    void aRefusedRequestSaysWhy() throws Exception {
        String[] s = signIn();
        HttpResponse<String> r =
                send("POST", "/api/runs/" + TRACE + "/pause", "{}", s[0], s[1], null);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(r.body()).get("message").asText()).isEqualTo("not now");
    }

    @Test
    void everySignInAndControlIsAudited_withoutAnyPassword() throws Exception {
        send("POST", "/api/login", "{\"password\":\"wrong-guess-123\"}", null, null, null);
        String[] s = signIn();
        send("POST", "/api/runs/" + TRACE + "/stop", "{}", s[0], s[1], null);
        send("POST", "/api/runs/" + TRACE + "/stop", "{}", s[0], "bad", null);
        assertThat(audit)
                .extracting(e -> e.get("action") + ":" + e.get("outcome"))
                .containsExactly(
                        "login:wrong password",
                        "login:signed in",
                        "stop:accepted",
                        "stop:refused: bad CSRF token or origin");
        assertThat(audit.toString())
                .doesNotContain("wrong-guess-123")
                .doesNotContain(password)
                .doesNotContain(s[1]);
        assertThat(audit.get(2))
                .containsEntry("run", TRACE)
                .containsEntry("client", "local")
                .containsEntry("who", "admin");
    }

    @Test
    void aRequestThatSaysItCameFromAnotherSiteIsRefused() throws Exception {
        String[] s = signIn();
        try (java.net.Socket sock = new java.net.Socket("127.0.0.1", server.port())) {
            String req =
                    "POST /api/runs/"
                            + TRACE
                            + "/stop HTTP/1.1\r\nHost: 127.0.0.1:"
                            + server.port()
                            + "\r\nOrigin: https://evil.example.com\r\nCookie: "
                            + s[0]
                            + "\r\nX-CSRF-Token: "
                            + s[1]
                            + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            sock.getOutputStream().write(req.getBytes());
            String reply = new String(sock.getInputStream().readAllBytes());
            assertThat(reply).startsWith("HTTP/1.1 403");
        }
        assertThat(asked).isEmpty();
    }

    @Test
    void withNoAdminPasswordSetNobodyCanSignIn_andTheSessionSaysWhy() throws Exception {
        password = null;
        enabled = false;
        assertThat(
                        send(
                                        "POST",
                                        "/api/login",
                                        "{\"password\":\"anything-at-all\"}",
                                        null,
                                        null,
                                        null)
                                .statusCode())
                .isEqualTo(503);
        JsonNode m = JSON.readTree(send("GET", "/api/session", null, null, null, null).body());
        assertThat(m.get("passwordSet").asBoolean()).isFalse();
        assertThat(m.get("controlsEnabled").asBoolean()).isFalse();
        assertThat(m.get("controlsReason").asText()).contains("LOOM_INSPECT_ADMIN_PASSWORD");
    }

    @Test
    void aWrongPasswordIsRefused_aFormPostIsRefused_andLoggingOutEndsTheSession() throws Exception {
        assertThat(
                        send(
                                        "POST",
                                        "/api/login",
                                        "{\"password\":\"nope-nope-nope\"}",
                                        null,
                                        null,
                                        null)
                                .statusCode())
                .isEqualTo(401);
        HttpRequest form =
                HttpRequest.newBuilder(URI.create(base + "/api/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("password=" + password))
                        .build();
        assertThat(http.send(form, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);
        String[] s = signIn();
        send("POST", "/api/logout", "{}", s[0], null, null);
        assertThat(
                        JSON.readTree(send("GET", "/api/session", null, s[0], null, null).body())
                                .get("signedIn")
                                .asBoolean())
                .isFalse();
    }
}
