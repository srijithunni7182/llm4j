package io.github.llm4j.getviral.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The hosted app end to end over real HTTP: sign in, onboard, start a run, stream it over SSE, answer
 * the human questions, get the pack and its media — and prove another creator can't see any of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(HostedTestConfig.class)
class HostedStudioTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    /** A browser: cookie jar + the SPA CSRF pattern (echo the XSRF-TOKEN cookie in a header). */
    final class Browser {
        final CookieManager cookies = new CookieManager();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> send(String method, String path, Object body) throws Exception {
            if (xsrf() == null) get("/api/public/info");
            HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
            String token = xsrf();
            if (token != null) b.header("X-XSRF-TOKEN", token);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        String xsrf() {
            return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue).findFirst().orElse(null);
        }

        Browser login(String email) throws Exception {
            assertThat(send("POST", "/auth/dev-login", Map.of("email", email)).statusCode()).isEqualTo(200);
            return this;
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static Map<String, Object> map(String json) throws Exception {
        return JSON.readValue(json, new TypeReference<>() { });
    }

    @Test
    void anonymousVisitorsCannotReachPersonalApis() throws Exception {
        Browser anon = new Browser();
        assertThat(anon.get("/api/me").statusCode()).isEqualTo(401);
        assertThat(anon.get("/api/runs").statusCode()).isEqualTo(401);
        assertThat(anon.get("/media/whatever/x.png").statusCode()).isEqualTo(401);
        assertThat(anon.get("/api/public/info").statusCode()).isEqualTo(200);
    }

    @Test
    void writesWithoutTheCsrfHeaderAreRejected() throws Exception {
        Browser user = new Browser().login("csrf@example.com");
        HttpResponse<String> response = user.http.send(HttpRequest.newBuilder(uri("/api/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"idea\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void creatorOnboardsRunsAPackAndOnlyTheyCanSeeIt() throws Exception {
        Browser maya = new Browser().login("maya@example.com");
        Map<String, Object> me = map(maya.get("/api/me").body());
        assertThat(me.get("onboardingStep")).isEqualTo("PROFILE");

        assertThat(maya.send("PUT", "/api/me/profile", Map.of("handle", "@maya.builds", "niche", "fitness",
                "tone", "bold and punchy", "region", "us")).statusCode()).isEqualTo(200);
        assertThat(maya.send("POST", "/api/me/voice", Map.of("posts", List.of(
                "Leg day is a lifestyle, not a punishment. Here's my 3-move finisher 🔥"))).statusCode()).isEqualTo(200);
        assertThat(map(maya.get("/api/me").body()).get("onboardingStep")).isEqualTo("CONNECT");

        HttpResponse<String> started = maya.send("POST", "/api/runs", Map.of("idea", "a 10-minute sunset workout on the beach"));
        assertThat(started.statusCode()).isEqualTo(201);
        String runId = (String) map(started.body()).get("id");

        // One active run per creator.
        assertThat(maya.send("POST", "/api/runs", Map.of("idea", "another idea")).statusCode()).isEqualTo(409);

        // Stream the run over SSE and act like the creator: pick hook #2, skip publishing.
        List<String> types = new ArrayList<>();
        HttpResponse<java.io.InputStream> stream = maya.http.send(HttpRequest.newBuilder(uri("/api/runs/" + runId + "/events"))
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                Map<String, Object> event = map(line.substring(5).trim());
                String type = (String) event.get("type");
                types.add(type);
                Map<?, ?> data = (Map<?, ?>) event.get("data");
                if ("human".equals(type)) {
                    String answer = "hook".equals(data.get("kind")) ? String.valueOf(((List<?>) data.get("options")).get(1)) : "skip";
                    assertThat(maya.send("POST", "/api/runs/" + runId + "/answer",
                            Map.of("id", data.get("id"), "answer", answer)).statusCode()).isEqualTo(200);
                }
                if ("status".equals(type) && List.of("DONE", "FAILED", "BLOCKED").contains(data.get("status"))) break;
            }
        }
        assertThat(types).contains("run_started", "prompt", "agent_done", "human", "media", "quality", "pack");

        Map<String, Object> run = map(maya.get("/api/runs/" + runId).body());
        assertThat(run.get("status")).isEqualTo("DONE");
        Map<?, ?> pack = (Map<?, ?>) run.get("pack");
        assertThat(pack.get("hook")).isNotNull();
        assertThat((List<?>) pack.get("media")).isNotEmpty();
        String mediaUrl = (String) ((Map<?, ?>) ((List<?>) pack.get("media")).get(0)).get("url");
        assertThat(maya.get(mediaUrl).statusCode()).isEqualTo(200);

        // The library lists the pack and every generated asset.
        assertThat(maya.get("/api/runs").body()).contains(runId);
        List<Map<String, Object>> library = JSON.readValue(maya.get("/api/library/media").body(), new TypeReference<>() { });
        assertThat(library).isNotEmpty().allSatisfy(m -> assertThat(m.get("runId")).isEqualTo(runId));

        // Memory is per account and survives in the database.
        assertThat(maya.get("/api/memory").body()).contains("picked the hook");

        // Another creator sees none of it.
        Browser sam = new Browser().login("sam@example.com");
        assertThat(sam.get("/api/runs/" + runId).statusCode()).isEqualTo(404);
        assertThat(sam.get("/api/runs/" + runId + "/events").statusCode()).isEqualTo(404);
        assertThat(sam.get(mediaUrl).statusCode()).isEqualTo(404);
        assertThat(sam.get("/api/runs").body()).doesNotContain(runId);
        assertThat(sam.get("/api/library/media").body()).isEqualTo("[]");
        assertThat(sam.get("/api/memory").body()).doesNotContain("picked the hook");
        assertThat(sam.send("POST", "/api/runs/" + runId + "/answer", Map.of("id", "x", "answer", "y")).statusCode()).isEqualTo(404);

        // Account deletion removes everything.
        assertThat(maya.send("DELETE", "/api/me", null).statusCode()).isEqualTo(200);
        Browser mayaAgain = new Browser().login("maya@example.com");
        assertThat(mayaAgain.get("/api/runs").body()).isEqualTo("[]");
        assertThat(map(mayaAgain.get("/api/me").body()).get("onboardingStep")).isEqualTo("PROFILE");
    }

    @Test
    void monthlyQuotaIsEnforced() throws Exception {
        Browser user = new Browser().login("quota@example.com");
        user.send("PUT", "/api/me/profile", Map.of("handle", "quota", "niche", "tech", "tone", "calm and wise"));
        // test profile: 3 packs per month, one at a time — mark each finished by running it through.
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> r = user.send("POST", "/api/runs", Map.of("idea", "idea number " + i));
            assertThat(r.statusCode()).isEqualTo(201);
            String id = (String) map(r.body()).get("id");
            finish(user, id);
        }
        HttpResponse<String> fourth = user.send("POST", "/api/runs", Map.of("idea", "one too many"));
        assertThat(fourth.statusCode()).isEqualTo(429);
        assertThat(fourth.body()).contains("3 packs");
    }

    /** Answers questions by polling the run until it finishes. */
    private void finish(Browser user, String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> run = map(user.get("/api/runs/" + runId).body());
            if (List.of("DONE", "FAILED", "BLOCKED").contains(run.get("status"))) return;
            for (Object q : (List<?>) run.get("openQuestions")) {
                Map<?, ?> question = (Map<?, ?>) q;
                String answer = "hook".equals(question.get("kind")) ? String.valueOf(((List<?>) question.get("options")).get(0)) : "skip";
                user.send("POST", "/api/runs/" + runId + "/answer", Map.of("id", question.get("id"), "answer", answer));
            }
            Thread.sleep(300);
        }
        throw new AssertionError("run " + runId + " did not finish");
    }
}
