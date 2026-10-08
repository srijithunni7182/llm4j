package io.github.llm4j.tools.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.tools.support.Declared;
import io.github.llm4j.tools.support.EchoServer;
import io.github.llm4j.tools.support.RecordingEffects;
import io.github.llm4j.agent.tool.EffectJournal;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebhookToolTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String SECRET_PATH = "/services/T0000/B0000/SECRETSECRET1234";

    @TempDir
    Path dir;
    MockWebServer server;
    RecordingEffects ctx;
    Map<String, String> env;
    Declared declared;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        ctx = new RecordingEffects();
        env = new java.util.HashMap<>();
        env.put("HOOK", server.url(SECRET_PATH).toString());
        declared = new Declared(env, dir);
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    Tool tool(String options) throws Exception {
        return declared.create("tool Hook { use: webhook  url: env.HOOK  " + options + " }", ctx);
    }

    /** Calls a tool; the generic tools never throw, so any exception is a test failure. */
    static String run(Tool tool, Map<String, Object> args) {
        try {
            return tool.execute(args);
        } catch (Exception e) {
            throw new AssertionError("a generic tool threw instead of returning an Error: " + e, e);
        }
    }

    JsonNode body(RecordedRequest r) throws IOException {
        return JSON.readTree(r.getBody().readUtf8());
    }

    @Test
    @Tag("V4.1")
    void slackGetsATextBodyWithABoldTitleAndNoUrlComesBack() throws Exception {
        server.enqueue(new MockResponse().setBody("ok"));

        String result = tool("format: slack").execute(Map.of("title", "Digest", "text", "Body"));

        RecordedRequest r = server.takeRequest();
        assertThat(r.getMethod()).isEqualTo("POST");
        assertThat(r.getPath()).isEqualTo(SECRET_PATH);
        assertThat(r.getHeader("Content-Type")).startsWith("application/json");
        assertThat(body(r).get("text").asText()).isEqualTo("*Digest*\nBody");
        assertThat(result).isEqualTo("Sent to slack webhook (HTTP 200).");
        assertThat(result).doesNotContain("SECRETSECRET");
    }

    @Test
    @Tag("V4.2")
    void discordTeamsAndJsonBodies() throws Exception {
        for (int i = 0; i < 4; i++) server.enqueue(new MockResponse().setBody("ok"));

        tool("format: discord").execute(Map.of("text", "x".repeat(2500)));
        JsonNode discord = body(server.takeRequest());
        assertThat(discord.get("content").asText()).hasSize(2000).endsWith("[cut]");

        tool("format: json").execute(Map.of("title", "T", "text", "B"));
        JsonNode plain = body(server.takeRequest());
        assertThat(plain.get("title").asText()).isEqualTo("T");
        assertThat(plain.get("text").asText()).isEqualTo("B");

        tool("format: teams").execute(Map.of("title", "T", "text", "B (teams)"));
        JsonNode teams = body(server.takeRequest());
        assertThat(teams.get("type").asText()).isEqualTo("message");
        JsonNode card = teams.get("attachments").get(0);
        assertThat(card.get("contentType").asText()).isEqualTo("application/vnd.microsoft.card.adaptive");
        assertThat(card.get("content").get("type").asText()).isEqualTo("AdaptiveCard");
        assertThat(card.get("content").get("body")).hasSize(2);
    }

    @Test
    @Tag("V4.3")
    void emptyOrOverlongTextIsRefusedWithNoRequest() throws Exception {
        Tool t = tool("format: slack");
        assertThat(t.execute(Map.of("text", " "))).startsWith("Error:").contains("text is required");
        assertThat(t.execute(Map.of())).startsWith("Error:");
        assertThat(t.execute(Map.of("text", "x".repeat(20_001)))).startsWith("Error:").contains("too long");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @Tag("V4.4")
    void retriesA429AfterTheTimeItAsksFor() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "1"));
        server.enqueue(new MockResponse().setBody("ok"));

        assertThat(tool("").execute(Map.of("text", "hi"))).startsWith("Sent");

        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(ctx.slept).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    @Tag("V4.4")
    void givesUpAfterTheConfiguredRetriesAndReportsTheStatus() throws Exception {
        for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        String result = tool("retries: 2").execute(Map.of("text", "hi"));

        assertThat(result).startsWith("Error:").contains("HTTP 500").contains("boom");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    @Tag("V4.4")
    void aLongRetryAfterAndOther4xxAreNotRetried() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "120"));
        assertThat(tool("").execute(Map.of("text", "hi"))).contains("rate limited").contains("retry after 120s");
        assertThat(ctx.slept).isEmpty();

        server.enqueue(new MockResponse().setResponseCode(404).setBody("no such hook"));
        assertThat(tool("").execute(Map.of("text", "again"))).contains("HTTP 404").contains("no such hook");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @Tag("V4.5")
    void aLiteralUrlIsALoadError() {
        assertThat(declared.problems("tool Hook { use: webhook  url: \"https://hooks.example.com/services/abc\" }"))
                .anyMatch(p -> p.contains("url must come from the environment"));
    }

    @Test
    @Tag("V4.6")
    void fixedHeadersAreSent() throws Exception {
        server.enqueue(new MockResponse().setBody("ok"));
        declared.create("tool Hook { use: webhook  url: env.HOOK  format: json  \"header.X-Source\": \"loom\" }", ctx)
                .execute(Map.of("text", "hi"));
        assertThat(server.takeRequest().getHeader("X-Source")).isEqualTo("loom");
    }

    @Test
    @Tag("V1.5")
    void theUrlNeverComesBackEvenWhenTheServerEchoesIt() throws Exception {
        try (EchoServer echo = new EchoServer()) {
            env.put("HOOK", echo.url("/error/services/T0000/B0000/SECRETSECRET1234"));
            String result = tool("retries: 0").execute(Map.of("text", "hi"));

            assertThat(result).startsWith("Error:").doesNotContain("SECRETSECRET1234").doesNotContain("T0000");
            assertThat(ctx.everything()).doesNotContain("SECRETSECRET1234");
        }
    }

    @Test
    @Tag("V4.7")
    void aResponseThatNeverComesLeavesTheCallPendingForTheResumePolicy() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        Tool t = declared.create("tool Hook { use: webhook  url: env.HOOK  timeout: 500ms }", ctx);

        String result = t.execute(Map.of("text", "hi"));

        assertThat(result).contains("may have been delivered");
        assertThat(ctx.journal().all().values()).extracting(EffectJournal.Entry::kind).containsExactly("effect_pending");
        // Resumed with the default policy (skip): it is not sent again.
        String resumed = declared.create("tool Hook { use: webhook  url: env.HOOK  timeout: 500ms }", ctx).execute(Map.of("text", "hi"));
        assertThat(resumed).contains("outcome is unknown");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @Tag("V4.7")
    void aConnectionRefusedIsRetriedAndEndsAsAFailureNotAnUnknown() throws Exception {
        int port = server.getPort();
        server.shutdown();
        env.put("HOOK", "http://localhost:" + port + "/hook");
        Tool t = declared.create("tool Hook { use: webhook  url: env.HOOK  allow_http: true  retries: 1 }", ctx);

        assertThat(t.execute(Map.of("text", "hi"))).startsWith("Error:").contains("couldn't reach");

        assertThat(ctx.slept).hasSize(1);
        assertThat(ctx.journal().all().values()).extracting(EffectJournal.Entry::kind).containsExactly("effect_failed");
        server = new MockWebServer();
        server.start();
    }

    @Test
    @Tag("V2.5")
    void idempotencyKeyIsSentWhenAskedForAndIsTheSameAfterAResume() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setBody("ok"));
        String decl = "tool Hook { use: webhook  url: env.HOOK  timeout: 500ms  idempotency: true }";

        declared.create(decl, ctx).execute(Map.of("text", "hi"));
        declared.create(decl, ctx).execute(Map.of("text", "hi")); // resumed: retried with the same key

        String first = server.takeRequest().getHeader("Idempotency-Key");
        String second = server.takeRequest().getHeader("Idempotency-Key");
        assertThat(first).isNotBlank().isEqualTo(second);
    }

    @Test
    @Tag("V2.2")
    void aResumedRunDoesNotSendTheSameMessageTwice() throws Exception {
        server.enqueue(new MockResponse().setBody("ok"));
        tool("").execute(Map.of("text", "hi"));

        String resumed = tool("").execute(Map.of("text", "hi"));

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(resumed).contains("already done");
    }

    @Test
    @Tag("H1")
    void hostileMessagesAreRefusedAndNothingIsSent() throws Exception {
        Tool t = tool("format: discord");

        assertThat(t.execute(Map.of("text", "hi", "title", "x\r\nHost: evil.example"))).startsWith("Error:").contains("single line");
        assertThat(t.execute(Map.of("text", "hi", "title", "x\nBcc: evil"))).startsWith("Error:");
        assertThat(t.execute(Map.of("text", "x".repeat(10 * 1024 * 1024)))).startsWith("Error:").contains("too long");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @Tag("H1")
    void aUrlArgumentCannotChangeTheDestination() throws Exception {
        server.enqueue(new MockResponse().setBody("ok"));

        tool("").execute(Map.of("text", "hi", "url", "https://evil.example/steal", "host", "evil.example"));

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(server.takeRequest().getPath()).isEqualTo(SECRET_PATH);
    }

    @Test
    @Tag("H1")
    void aRedirectToTheMetadataAddressIsNotFollowed() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(301).setHeader("Location", "http://169.254.169.254/latest/meta-data/"));

        String result = tool("retries: 0").execute(Map.of("text", "hi"));

        assertThat(result).startsWith("Error:").contains("redirect").contains("not delivered");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @Tag("V1.8")
    void theDescriptionTellsTheModelTheArgumentsAndTheScriptsDescriptionFollows() throws Exception {
        Tool t = declared.create("tool Hook { use: webhook  url: env.HOOK  format: discord  description: \"Use for ops alerts only.\" }", ctx);

        assertThat(t.getDescription()).contains("discord webhook").contains("text (required").contains("title").endsWith("Use for ops alerts only.");
    }

    @Test
    @Tag("V1.2")
    void badOptionsAreLoadErrors() {
        assertThat(declared.problems("tool Hook { use: webhook  url: env.HOOK  format: sms }")).anyMatch(p -> p.contains("format"));
        assertThat(declared.problems("tool Hook { use: webhook  url: env.HOOK  retries: 9 }")).anyMatch(p -> p.contains("retries"));
        assertThat(declared.problems("tool Hook { use: webhook  url: env.HOOK  timeout: soon }")).anyMatch(p -> p.contains("timeout"));
        assertThat(declared.problems("tool Hook { use: webhook  format: slack }")).anyMatch(p -> p.contains("needs url"));
        assertThat(declared.problems("tool Hook { use: webhook  url: env.MISSING }")).anyMatch(p -> p.contains("MISSING"));
        assertThat(declared.problems("tool Hook { use: webhook  url: env.HOOK  colour: red }")).anyMatch(p -> p.contains("unknown option colour"));
        env.put("HOOK", "http://example.com/hook");
        assertThat(declared.problems("tool Hook { use: webhook  url: env.HOOK }")).anyMatch(p -> p.contains("only https"));
        env.put("HOOK", "abc");
        assertThat(declared.problems("tool Hook { use: webhook  url: env.HOOK }")).anyMatch(p -> p.contains("too short"));
    }
}
