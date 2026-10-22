package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.inspect.control.AuditSink;
import io.github.llm4j.loom.inspect.control.Controls;
import io.github.llm4j.loom.inspect.control.Secrets;
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
import org.junit.jupiter.api.Test;

/**
 * Who may see the secrets list and replace a value, and what is recorded. A fake store, a real
 * server.
 */
class InspectSecretsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct horse battery";

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Map<String, Object>> audit = Collections.synchronizedList(new ArrayList<>());
    private final List<String> rotated = Collections.synchronizedList(new ArrayList<>());
    private InspectServer server;
    private String base;

    private final Secrets secrets =
            new Secrets() {
                @Override
                public List<Map<String, Object>> list() {
                    return List.of(Map.of("name", "OPENAI_KEY", "fingerprint", "3b1e…90"));
                }

                @Override
                public Controls.Outcome rotate(String name, char[] value, String who) {
                    rotated.add(name + "=" + new String(value) + " by " + who);
                    return name.equals("MISSING")
                            ? Controls.Outcome.refused(404, "no such secret")
                            : Controls.Outcome.taken("rotated");
                }
            };

    @BeforeEach
    void start() {
        AdminSessions sessions = new AdminSessions(() -> PASSWORD, Clock.systemUTC(), false);
        server =
                new InspectServer(
                        new RunRegistry(),
                        InspectServer.config()
                                .bind("127.0.0.1", 0)
                                .sessions(sessions)
                                .audit((AuditSink) audit::add)
                                .secrets(secrets));
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> send(
            String method, String path, String body, String cookie, String csrf) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (body != null) b.header("Content-Type", "application/json");
        if (cookie != null) b.header("Cookie", cookie);
        if (csrf != null) b.header("X-CSRF-Token", csrf);
        b.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String[] signIn() throws Exception {
        HttpResponse<String> r =
                send("POST", "/api/login", "{\"password\":\"" + PASSWORD + "\"}", null, null);
        return new String[] {
            r.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],
            JSON.readTree(r.body()).get("csrf").asText()
        };
    }

    @Test
    void theListIsForASignedInAdministratorOnly_andCarriesNoValue() throws Exception {
        assertThat(send("GET", "/api/secrets", null, null, null).statusCode()).isEqualTo(401);
        String[] s = signIn();
        HttpResponse<String> r = send("GET", "/api/secrets", null, s[0], null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).at("/secrets/0/fingerprint").asText())
                .isEqualTo("3b1e…90");
        assertThat(
                        JSON.readTree(send("GET", "/api/session", null, null, null).body())
                                .get("secretsEnabled")
                                .asBoolean())
                .isTrue();
    }

    @Test
    void aRotationNeedsTheSession_theToken_andThePasswordAgain() throws Exception {
        String body = "{\"value\":\"sk-new-value-123\",\"password\":\"" + PASSWORD + "\"}";
        assertThat(send("PUT", "/api/secrets/OPENAI_KEY", body, null, null).statusCode())
                .isEqualTo(401);
        String[] s = signIn();
        assertThat(send("PUT", "/api/secrets/OPENAI_KEY", body, s[0], "wrong-token").statusCode())
                .isEqualTo(403);
        assertThat(
                        send(
                                        "PUT",
                                        "/api/secrets/OPENAI_KEY",
                                        "{\"value\":\"sk-new-value-123\",\"password\":\"not it at all\"}",
                                        s[0],
                                        s[1])
                                .statusCode())
                .isEqualTo(403);
        assertThat(rotated).isEmpty();
        assertThat(
                        send(
                                        "PUT",
                                        "/api/secrets/OPENAI_KEY",
                                        "{\"password\":\"" + PASSWORD + "\"}",
                                        s[0],
                                        s[1])
                                .statusCode())
                .isEqualTo(400); // no value
        assertThat(send("PUT", "/api/secrets/bad%20name!", body, s[0], s[1]).statusCode())
                .isEqualTo(404);
        assertThat(rotated).isEmpty();

        HttpResponse<String> ok = send("PUT", "/api/secrets/OPENAI_KEY", body, s[0], s[1]);
        assertThat(ok.statusCode()).isEqualTo(202);
        assertThat(ok.body()).doesNotContain("sk-new-value");
        assertThat(rotated)
                .singleElement()
                .asString()
                .startsWith("OPENAI_KEY=sk-new-value-123 by admin@");
        assertThat(send("PUT", "/api/secrets/MISSING", body, s[0], s[1]).statusCode())
                .isEqualTo(404);
    }

    @Test
    void everyAttemptIsAuditedWithTheNameAndOutcome_neverTheValueOrThePassword() throws Exception {
        String[] s = signIn();
        send(
                "PUT",
                "/api/secrets/OPENAI_KEY",
                "{\"value\":\"sk-new-value-123\",\"password\":\"nope\"}",
                s[0],
                s[1]);
        send(
                "PUT",
                "/api/secrets/OPENAI_KEY",
                "{\"value\":\"sk-new-value-123\",\"password\":\"" + PASSWORD + "\"}",
                s[0],
                s[1]);
        String all = audit.toString();
        assertThat(all)
                .contains("rotate-secret")
                .contains("OPENAI_KEY")
                .contains("rotated")
                .contains("password not confirmed");
        assertThat(all)
                .doesNotContain("sk-new-value")
                .doesNotContain(PASSWORD)
                .doesNotContain(s[1]);
    }

    @Test
    void wrongPasswordsOnARotationCountAgainstTheSameLockout() throws Exception {
        String[] s = signIn();
        String wrong = "{\"value\":\"v-1234567\",\"password\":\"nope\"}";
        for (int i = 0; i < 4; i++) send("PUT", "/api/secrets/OPENAI_KEY", wrong, s[0], s[1]);
        // locked out: even the right password is refused for a while
        assertThat(
                        send(
                                        "PUT",
                                        "/api/secrets/OPENAI_KEY",
                                        "{\"value\":\"v-1234567\",\"password\":\""
                                                + PASSWORD
                                                + "\"}",
                                        s[0],
                                        s[1])
                                .statusCode())
                .isEqualTo(403);
        assertThat(rotated).isEmpty();
    }
}
