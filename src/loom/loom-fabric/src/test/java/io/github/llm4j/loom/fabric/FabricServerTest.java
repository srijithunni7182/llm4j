package io.github.llm4j.loom.fabric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.wire.Hello;
import io.github.llm4j.loom.inspect.wire.Signer;
import io.github.llm4j.loom.inspect.wire.Wire;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server over real HTTP on loopback: who may report, what is kept, what survives a restart, who
 * may look.
 */
class FabricServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUN = "r-1";
    private static final String TRACE = Ids.traceId(RUN);

    @TempDir Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private Vault vault;
    private FabricServer server;
    private String base;
    private String keyA;

    @BeforeEach
    void start() {
        vault = Vault.create(dir.resolve("vault.json"), "a-long-enough-master-key");
        keyA = Vault.newClientKey();
        vault.put("client.alpha", keyA);
        vault.put("client.beta", Vault.newClientKey());
        vault.put(Vault.ADMIN, "correct horse battery");
        boot(100, 200);
    }

    private void boot(double rate, double burst) {
        server =
                new FabricServer(
                        new FabricServer.Settings(dir, "127.0.0.1", 0, null, false, 30, 2L << 30),
                        vault,
                        new PrintStream(console),
                        rate,
                        burst);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> post(String path, String client, String key, String body)
            throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(base + path))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .header("Content-Type", "application/json");
        if (client != null) b.header(Signer.CLIENT_HEADER, client);
        if (key != null) b.header(Signer.HEADER, Signer.sign(key, Instant.now(), bytes));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String logs(String service, long seq, String event) {
        LogRec r =
                new LogRec(
                        1_000_000_000L * (1 + seq),
                        LogRec.INFO,
                        "w",
                        Map.of(
                                Semconv.EVENT_NAME,
                                event,
                                Semconv.LOOM_SEQ,
                                seq,
                                Semconv.LOOM_RUN_ID,
                                RUN,
                                Semconv.LOOM_WORKFLOW,
                                "Main"),
                        TRACE,
                        null);
        return OtlpJson.encodeLogs(Resource.of(service, "i_1"), "0.1", List.of(r));
    }

    private static String hello(String name) {
        return Wire.write(
                new Hello(
                        name,
                        "i_1",
                        "h",
                        1,
                        "5.0",
                        "0.1",
                        Wire.VERSION,
                        List.of(new Hello.Workflow("Main", "x"))));
    }

    private String login() throws Exception {
        HttpResponse<String> r =
                http.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/login"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"password\":\"correct horse battery\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return r.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private HttpResponse<String> api(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (cookie != null) b.header("Cookie", cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aSignedMessageIsAppliedAndAcknowledged_aMissingWrongOrUnknownKeyAreAllTheSame401()
            throws Exception {
        assertThat(post("/loom-inspect/v1/hello", "alpha", keyA, hello("alpha")).statusCode())
                .isEqualTo(200);
        assertThat(
                        post("/v1/logs", "alpha", keyA, logs("alpha", 1, Semconv.EV_RUN_START))
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        post("/v1/logs", "alpha", null, logs("alpha", 2, Semconv.EV_RUN_START))
                                .statusCode())
                .isEqualTo(401);
        assertThat(
                        post(
                                        "/v1/logs",
                                        "alpha",
                                        "lfk_wrong",
                                        logs("alpha", 2, Semconv.EV_RUN_START))
                                .statusCode())
                .isEqualTo(401);
        assertThat(
                        post("/v1/logs", "nobody", keyA, logs("nobody", 2, Semconv.EV_RUN_START))
                                .statusCode())
                .isEqualTo(401);
        assertThat(post("/v1/logs", "../etc", keyA, "{}").statusCode()).isEqualTo(401);
        assertThat(server.registry().findByTrace(TRACE).orElseThrow().view())
                .containsEntry("client", "alpha")
                .containsEntry("status", "running");
    }

    @Test
    void aClientCannotReportAsAnotherClient() throws Exception {
        HttpResponse<String> r =
                post("/v1/logs", "alpha", keyA, logs("beta", 1, Semconv.EV_RUN_START));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(server.registry().summaries()).isEmpty();
    }

    @Test
    void anOldSignatureIsRefused_soAReplayedBatchIsNotAccepted() throws Exception {
        byte[] bytes = logs("alpha", 1, Semconv.EV_RUN_START).getBytes(StandardCharsets.UTF_8);
        HttpRequest old =
                HttpRequest.newBuilder(URI.create(base + "/v1/logs"))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .header(Signer.CLIENT_HEADER, "alpha")
                        .header(
                                Signer.HEADER,
                                Signer.sign(keyA, Instant.now().minusSeconds(600), bytes))
                        .build();
        assertThat(http.send(old, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }

    @Test
    void garbageIsA400_andARevokedClientIsRefusedAtOnce() throws Exception {
        assertThat(post("/v1/logs", "alpha", keyA, "{not json").statusCode()).isEqualTo(400);
        assertThat(post("/loom-inspect/v1/hello", "alpha", keyA, "[1,2").statusCode())
                .isEqualTo(400);
        vault.remove("client.alpha");
        assertThat(post("/loom-inspect/v1/hello", "alpha", keyA, hello("alpha")).statusCode())
                .isEqualTo(401);
    }

    @Test
    void whatWasAcknowledgedSurvivesARestart_andTheProcessReadsAsOfflineUntilItReturns()
            throws Exception {
        post("/loom-inspect/v1/hello", "alpha", keyA, hello("alpha"));
        post("/v1/logs", "alpha", keyA, logs("alpha", 1, Semconv.EV_RUN_START));
        server.close();
        boot(100, 200);
        assertThat(server.registry().findByTrace(TRACE).orElseThrow().view())
                .containsEntry("workflow", "Main")
                .containsEntry("client", "alpha");
        Map<String, Object> p = server.receiver().processes().get(0);
        assertThat(p).containsEntry("name", "alpha");
        assertThat(console.toString()).contains("restored 2 stored messages");
        // restored as of when it was stored: just now, so online; an hour-old history would read
        // offline, which the receiver test covers
    }

    @Test
    void aRestoredRunHasItsHistoryAgain_soItCanBeShownAsItWasAfterARestart() throws Exception {
        post("/loom-inspect/v1/hello", "alpha", keyA, hello("alpha"));
        post("/v1/logs", "alpha", keyA, logs("alpha", 1, Semconv.EV_RUN_START));
        io.github.llm4j.loom.inspect.state.RunState before =
                server.registry().findByTrace(TRACE).orElseThrow();
        long seqBefore = before.lastSeq();
        server.close();
        boot(100, 200);
        io.github.llm4j.loom.inspect.state.RunState after =
                server.registry().findByTrace(TRACE).orElseThrow();
        assertThat(after.lastSeq()).isEqualTo(seqBefore);
        assertThat(after.firstSeq()).isZero();
        assertThat(after.viewAt(after.lastSeq())).isPresent();
        assertThat(after.timeline().get(0)).containsEntry("kind", "start");
    }

    @Test
    void theApiNeedsALogin_aWrongPasswordIsRefused_andAfterRepeatedFailuresTheAddressMustWait()
            throws Exception {
        assertThat(api("/api/runs", null).statusCode()).isEqualTo(401);
        assertThat(api("/api/processes", null).statusCode()).isEqualTo(401);
        assertThat(api("/api/health", null).statusCode()).isEqualTo(200);
        String cookie = login();
        assertThat(api("/api/runs", cookie).statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(api("/api/processes", cookie).body()).get("processes"))
                .isNotNull();

        int last = 0;
        for (int i = 0; i < 4 && last != 429; i++) {
            last =
                    http.send(
                                    HttpRequest.newBuilder(URI.create(base + "/api/login"))
                                            .header("Content-Type", "application/json")
                                            .POST(
                                                    HttpRequest.BodyPublishers.ofString(
                                                            "{\"password\":\"nope\"}"))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode();
        }
        assertThat(last).isEqualTo(429);
        assertThat(console.toString()).contains("failed login").doesNotContain("nope");
    }

    @Test
    void aLoginFromAnotherSiteOrWithoutJsonIsRefused_andLogoutEndsTheSession() throws Exception {
        HttpResponse<String> form =
                http.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/login"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "password=correct+horse+battery"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(form.statusCode()).isEqualTo(403);
        String cookie = login();
        http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/logout"))
                        .header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(api("/api/runs", cookie).statusCode()).isEqualTo(401);
    }

    @Test
    void theSessionCookieCannotBeReadByScriptAndIsNotSentCrossSite() throws Exception {
        HttpResponse<String> r =
                http.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/login"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"password\":\"correct horse battery\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(r.headers().firstValue("Set-Cookie").orElseThrow())
                .contains("HttpOnly")
                .contains("SameSite=Strict");
    }

    @Test
    void overTheRateLimitTheServerAsksTheProcessToSlowDown() throws Exception {
        server.close();
        boot(1, 3);
        int tooMany = 0;
        for (int i = 0; i < 8; i++) {
            HttpResponse<String> r =
                    post(
                            "/loom-inspect/v1/heartbeat",
                            "alpha",
                            keyA,
                            "{\"client\":\"alpha\",\"instance\":\"i\",\"at\":\"x\"}");
            if (r.statusCode() == 429) {
                tooMany++;
                assertThat(r.headers().firstValue("Retry-After")).isPresent();
            }
        }
        assertThat(tooMany).isGreaterThan(0);
    }

    @Test
    void withoutTlsItWillNotListenOnAPublicAddress_butBehindAProxyItMay() {
        assertThatThrownBy(
                        () ->
                                new FabricServer(
                                        new FabricServer.Settings(
                                                dir, "0.0.0.0", 0, null, false, 30, 1L << 30),
                                        vault,
                                        new PrintStream(console)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without TLS");
        FabricServer behind =
                new FabricServer(
                        new FabricServer.Settings(dir, "0.0.0.0", 0, null, true, 30, 1L << 30),
                        vault,
                        new PrintStream(console));
        behind.close();
    }

    @Test
    void anAcknowledgedHeartbeatIsNotStoredButALogBatchIs() throws Exception {
        post(
                "/loom-inspect/v1/heartbeat",
                "alpha",
                keyA,
                "{\"client\":\"alpha\",\"instance\":\"i\",\"at\":\"x\"}");
        post("/v1/logs", "alpha", keyA, logs("alpha", 1, Semconv.EV_RUN_START));
        List<String> kinds = new java.util.ArrayList<>();
        server.history().replay(e -> kinds.add(e.kind()));
        assertThat(kinds).containsExactly("logs");
    }

    @Test
    void aStopFromThePageBecomesASignedCommandOnlyItsProcessCanCollect_andItsResultIsAudited()
            throws Exception {
        assertThat(post("/loom-inspect/v1/hello", "alpha", keyA, hello("alpha")).statusCode())
                .isEqualTo(200);
        assertThat(
                        post("/v1/logs", "alpha", keyA, logs("alpha", 1, Semconv.EV_RUN_START))
                                .statusCode())
                .isEqualTo(200);
        String cookie = login();
        JsonNode session =
                JSON.readTree(
                        http.send(
                                        HttpRequest.newBuilder(URI.create(base + "/api/session"))
                                                .header("Cookie", cookie)
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString())
                                .body());
        String csrf = session.get("csrf").asText();
        JsonNode view = JSON.readTree(api("/api/runs/" + TRACE, cookie).body());
        assertThat(view.at("/controls/allowed").toString()).contains("stop").contains("pause");

        HttpResponse<String> stop =
                http.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/runs/" + TRACE + "/stop"))
                                .header("Cookie", cookie)
                                .header("X-CSRF-Token", csrf)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(stop.statusCode()).isEqualTo(202);

        // another process gets nothing, and an unsigned poll is refused
        assertThat(
                        post(
                                        "/loom-inspect/v1/commands",
                                        "beta",
                                        server == null ? null : vault.get("client.beta"),
                                        "{\"wait\":0}")
                                .body())
                .contains("\"commands\":[]");
        assertThat(post("/loom-inspect/v1/commands", "alpha", null, "{\"wait\":0}").statusCode())
                .isEqualTo(401);

        HttpResponse<String> poll =
                post("/loom-inspect/v1/commands", "alpha", keyA, "{\"wait\":0}");
        assertThat(poll.statusCode()).isEqualTo(200);
        assertThat(
                        Signer.verify(
                                keyA,
                                poll.headers().firstValue(Signer.HEADER).orElse(null),
                                poll.body().getBytes(StandardCharsets.UTF_8),
                                Instant.now()))
                .isTrue();
        JsonNode cmd = JSON.readTree(poll.body()).at("/commands/0");
        assertThat(cmd.get("action").asText()).isEqualTo("stop");
        assertThat(cmd.get("trace").asText()).isEqualTo(TRACE);
        assertThat(JSON.readTree(poll.body()).get("client").asText()).isEqualTo("alpha");
        assertThat(
                        JSON.readTree(
                                        post(
                                                        "/loom-inspect/v1/commands",
                                                        "alpha",
                                                        keyA,
                                                        "{\"wait\":0}")
                                                .body())
                                .get("commands"))
                .isEmpty(); // handed out once

        assertThat(
                        post(
                                        "/loom-inspect/v1/command-result",
                                        "alpha",
                                        keyA,
                                        "{\"id\":\""
                                                + cmd.get("id").asText()
                                                + "\",\"accepted\":true,\"message\":\"stop requested\"}")
                                .statusCode())
                .isEqualTo(200);
        String audit = java.nio.file.Files.readString(dir.resolve("audit.log"));
        assertThat(audit)
                .contains("\"action\":\"stop\"")
                .contains("command-result")
                .contains("stop requested")
                .doesNotContain("correct horse");
    }

    private HttpResponse<String> put(String path, String body, String cookie, String csrf)
            throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) b.header("Cookie", cookie);
        if (csrf != null) b.header("X-CSRF-Token", csrf);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String csrf(String cookie) throws Exception {
        return JSON.readTree(
                        http.send(
                                        HttpRequest.newBuilder(URI.create(base + "/api/session"))
                                                .header("Cookie", cookie)
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString())
                                .body())
                .get("csrf")
                .asText();
    }

    @Test
    void theConfigurationNeedsASessionAndTheToken_aBadFileIsRefusedWithItsLinesAndNothingIsKept()
            throws Exception {
        assertThat(api("/api/config", null).statusCode()).isEqualTo(401);
        String cookie = login();
        String csrf = csrf(cookie);
        assertThat(
                        put(
                                        "/api/config/rules",
                                        "{\"text\":\"Editor { same_action: 3 times }\"}",
                                        null,
                                        null)
                                .statusCode())
                .isEqualTo(401);
        assertThat(
                        put(
                                        "/api/config/rules",
                                        "{\"text\":\"Editor { same_action: 3 times }\"}",
                                        cookie,
                                        "wrong")
                                .statusCode())
                .isEqualTo(403);

        HttpResponse<String> bad =
                put(
                        "/api/config/rules",
                        "{\"text\":\"Editor {\\n  same_action: 0 times\\n  made_up: 3\\n}\"}",
                        cookie,
                        csrf);
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(bad.body()).get("problems").toString()).contains("made_up");
        assertThat(
                        JSON.readTree(api("/api/config", cookie).body())
                                .at("/sections/rules/text")
                                .asText())
                .isEmpty(); // nothing kept

        HttpResponse<String> badPrices =
                put("/api/config/prices", "{\"text\":\"gemini = cheap\"}", cookie, csrf);
        assertThat(badPrices.statusCode()).isEqualTo(422);
        assertThat(put("/api/config/other", "{\"text\":\"x\"}", cookie, csrf).statusCode())
                .isEqualTo(404);
    }

    @Test
    void savedRulesAndPricesAreInForceAtOnce_surviveARestart_andAreAudited() throws Exception {
        String cookie = login();
        String csrf = csrf(cookie);
        assertThat(
                        put(
                                        "/api/config/rules",
                                        "{\"text\":\"* { same_action: 2 times }\\n\"}",
                                        cookie,
                                        csrf)
                                .statusCode())
                .isEqualTo(200);
        HttpResponse<String> ok =
                put("/api/config/prices", "{\"text\":\"m = 1 / 2\\n\"}", cookie, csrf);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(ok.body()).get("message").asText()).contains("1 model");
        JsonNode view = JSON.readTree(api("/api/config", cookie).body());
        assertThat(view.at("/sections/rules/summary").asText()).contains("1 block");
        assertThat(view.at("/sections/prices/text").asText()).contains("m = 1 / 2");

        server.close();
        boot(100, 200);
        String again = login();
        JsonNode after = JSON.readTree(api("/api/config", again).body());
        assertThat(after.at("/sections/rules/text").asText()).contains("same_action: 2 times");
        assertThat(after.at("/sections/prices/summary").asText()).contains("1 model");
        String audit = java.nio.file.Files.readString(dir.resolve("audit.log"));
        assertThat(audit)
                .contains("change-config")
                .contains("rules")
                .contains("prices")
                .contains("saved");
    }

    @Test
    void
            theStructuredEditorsSaveRulesAndPricesAsText_theCatalogOffersTheChoices_andABadStructureIsRefused()
                    throws Exception {
        assertThat(post("/loom-inspect/v1/hello", "alpha", keyA, hello("alpha")).statusCode())
                .isEqualTo(200);
        assertThat(
                        post("/v1/logs", "alpha", keyA, logs("alpha", 1, Semconv.EV_RUN_START))
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        post(
                                        "/loom-inspect/v1/snapshot",
                                        "alpha",
                                        keyA,
                                        Wire.write(
                                                new io.github.llm4j.loom.inspect.wire.Snapshot(
                                                        "alpha",
                                                        "i_1",
                                                        TRACE,
                                                        RUN,
                                                        "Main",
                                                        Map.of(),
                                                        List.of(),
                                                        Map.of(),
                                                        Map.of(
                                                                "Writer",
                                                                "gemini-2.5-flash",
                                                                "Editor",
                                                                "gemini-2.5-flash"))))
                                .statusCode())
                .isEqualTo(200);
        String cookie = login();
        String csrf = csrf(cookie);

        JsonNode view = JSON.readTree(api("/api/config", cookie).body());
        assertThat(view.at("/catalog/agents").toString()).contains("Editor").contains("Writer");
        assertThat(view.at("/catalog/models/0/model").asText()).isEqualTo("gemini-2.5-flash");
        assertThat(view.at("/sections/rules/defaults/same_action").asInt()).isEqualTo(4);

        HttpResponse<String> rules =
                put(
                        "/api/config/rules",
                        "{\"structured\":{\"Editor\":{\"same_action\":3,\"no_progress\":{\"rounds\":2,\"variable\":\"review.fix\"}},"
                                + "\"*\":{\"paused_longer_than\":5400,\"ungrounded_figures\":{\"warn\":2,\"high\":4}}}}",
                        cookie,
                        csrf);
        assertThat(rules.statusCode()).isEqualTo(200);
        JsonNode after = JSON.readTree(api("/api/config", cookie).body());
        assertThat(after.at("/sections/rules/text").asText())
                .contains("Editor {")
                .contains("same_action: 3 times")
                .contains("paused_longer_than: 90m")
                .contains("no_progress: 2 rounds on review.fix");
        assertThat(after.at("/sections/rules/structured/Editor/same_action").asInt()).isEqualTo(3);
        assertThat(after.at("/sections/rules/structured/*/ungrounded_figures/high").asInt())
                .isEqualTo(4);

        HttpResponse<String> prices =
                put(
                        "/api/config/prices",
                        "{\"structured\":[{\"model\":\"gemini-2.5-flash\",\"input\":\"0.30\",\"output\":\"2.5\"}]}",
                        cookie,
                        csrf);
        assertThat(prices.statusCode()).isEqualTo(200);
        JsonNode p2 = JSON.readTree(api("/api/config", cookie).body());
        assertThat(p2.at("/sections/prices/structured/0/input").asText()).isEqualTo("0.3");
        assertThat(p2.at("/sections/prices/structured/0/output").asText()).isEqualTo("2.5");

        // a bad structure is refused with its reason, and nothing is kept
        assertThat(
                        put(
                                        "/api/config/prices",
                                        "{\"structured\":[{\"model\":\"x\",\"input\":\"cheap\",\"output\":\"1\"}]}",
                                        cookie,
                                        csrf)
                                .statusCode())
                .isEqualTo(400);
        assertThat(
                        put(
                                        "/api/config/rules",
                                        "{\"structured\":{\"Editor\":{\"same_action\":0}}}",
                                        cookie,
                                        csrf)
                                .statusCode())
                .isEqualTo(422);
        assertThat(
                        JSON.readTree(api("/api/config", cookie).body())
                                .at("/sections/prices/structured")
                                .size())
                .isEqualTo(1);
    }
}
