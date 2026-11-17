package io.github.llm4j.examples.expensedesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The desk over HTTP, on a free loopback port. */
class DeskServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;
    private Desk desk;
    private DeskServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        desk = new Desk(Path.of("expense.loom"), dir, new OfflineModels(), Clock.systemUTC());
        server = new DeskServer(desk, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), "offline", "");
    }

    @AfterEach
    void stop() {
        server.close();
        desk.close();
    }

    @Test
    void servesThePageAndTheArchitecture() throws Exception {
        assertThat(get("/").body()).contains("<title>Expense Desk</title>");

        JsonNode architecture = JSON.readTree(get("/api/architecture").body());
        assertThat(architecture.get("script").asText()).contains("workflow Claim");
        assertThat(architecture.get("graph").get("workflows")).hasSize(1);
        assertThat(architecture.get("audit").get("findings")).isEmpty();
    }

    @Test
    void takesAClaimAndADecisionAndShowsThePayment() throws Exception {
        HttpResponse<String> made = post("/api/claims", "{\"employee\":\"Ravi\",\"text\":\"Hotel at Grand Plaza, $640\"}");
        assertThat(made.statusCode()).isEqualTo(202);
        String id = JSON.readTree(made.body()).get("id").asText();
        awaitStatus(id, "WAITING");

        assertThat(post("/api/claims/" + id + "/decision", "{\"approve\":true,\"by\":\"Meera\"}").statusCode()).isEqualTo(202);
        awaitStatus(id, "PAID");

        assertThat(JSON.readTree(get("/api/state").body()).get("payments")).hasSize(1);
        assertThat(post("/api/claims/" + id + "/decision", "{\"approve\":true,\"by\":\"Meera\"}").statusCode()).isEqualTo(409);
    }

    @Test
    void refusesWhatItShouldNotTake() throws Exception {
        assertThat(post("/api/claims", "{\"employee\":\"Asha\",\"text\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(post("/api/claims", "not json").statusCode()).isEqualTo(400);
        assertThat(post("/api/claims", "{\"employee\":\"Asha\",\"text\":\"x\",\"admin\":true}").statusCode()).isEqualTo(400);
        assertThat(post("/api/claims", "{\"employee\":\"Asha\",\"text\":\"" + "x".repeat(20_000) + "\"}").statusCode()).isEqualTo(413);
        assertThat(post("/api/claims/claim-none/decision", "{\"approve\":true,\"by\":\"Meera\"}").statusCode()).isEqualTo(400);
        assertThat(get("/api/nothing").statusCode()).isEqualTo(404);

        HttpRequest form = HttpRequest.newBuilder(uri("/api/claims"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("employee=Asha&text=Taxi+%2442"))
                .build();
        assertThat(http.send(form, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
        assertThat(desk.claims()).isEmpty();
    }

    private void awaitStatus(String id, String wanted) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            for (JsonNode claim : JSON.readTree(get("/api/state").body()).get("claims")) {
                if (claim.get("id").asText().equals(id) && claim.get("status").asText().equals(wanted)) return;
            }
            if (System.nanoTime() > deadline) throw new AssertionError("claim " + id + " never became " + wanted);
            Thread.onSpinWait();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    /** A page on another site that has pointed its own name at 127.0.0.1 (DNS rebinding) is refused. */
    @Test
    void aRequestForAnotherNameOrFromAnotherSiteIsRefused() throws Exception {
        java.net.Socket socket = new java.net.Socket("127.0.0.1", server.port());
        try (socket) {
            socket.getOutputStream().write(("GET /api/state HTTP/1.1\r\nHost: evil.example:" + server.port() + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            String reply = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
            assertThat(reply).startsWith("HTTP/1.1 403");
        }

        HttpRequest fromElsewhere = HttpRequest.newBuilder(uri("/api/claims"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("{\"employee\":\"Asha\",\"text\":\"Taxi, $10\"}"))
                .build();
        assertThat(http.send(fromElsewhere, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(desk.claims()).isEmpty();
    }

    @Test
    void theDesksOwnPageIsServedForLocalhostAndItsOwnOrigin() throws Exception {
        HttpRequest own = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/state"))
                .header("Origin", "http://localhost:" + server.port())
                .build();

        assertThat(http.send(own, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }
}
