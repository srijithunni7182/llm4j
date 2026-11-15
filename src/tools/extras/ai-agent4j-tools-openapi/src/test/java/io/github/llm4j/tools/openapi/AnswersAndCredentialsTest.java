package io.github.llm4j.tools.openapi;

import static io.github.llm4j.tools.openapi.Fixtures.options;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.secret.CredentialSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a tool returns for an answer, and how it carries a credential (phase 36a; guards G72, G73 and
 * G74), against the recorded Petstore description and answers.
 */
class AnswersAndCredentialsTest {

    @TempDir Path dir;
    Fixtures.Tls tls;
    final Fixtures.Context context = new Fixtures.Context();

    @BeforeEach
    void start() throws IOException {
        tls = new Fixtures.Tls();
        Fixtures.fixture(dir, "openapi.json", "petstore.json");
    }

    @AfterEach
    void stop() throws IOException {
        tls.close();
    }

    private Tool tool(String operation, String... more) throws Exception {
        List<String> all =
                new ArrayList<>(
                        List.of(
                                "spec", "petstore.json", "operation", operation, "base_url", tls.url("/api/v3")));
        all.addAll(List.of(more));
        return tls.kind.create("Pet_" + operation, options(all.toArray(String[]::new)), dir, context);
    }

    private Tool withSource(String operation, CredentialSource source) throws Exception {
        return tls.kind.create(
                "Pet_" + operation,
                options("spec", "petstore.json", "operation", operation, "base_url", tls.url("/api/v3"), "auth_header", "Authorization"),
                Map.of("auth_value", source),
                dir,
                context);
    }

    // ── what a recorded real answer looks like through the tool ──────────────────────────────

    @Test
    void aRecordedNotFoundIsShownWithItsStatusAndType() throws Exception {
        tls.server.enqueue(
                new MockResponse()
                        .setResponseCode(404)
                        .setHeader("Content-Type", "application/xml")
                        .setBody(Fixtures.text("pet-1-not-found.txt")));

        assertThat(tool("getPetById").execute(Map.of("petId", 1)))
                .startsWith("HTTP 404")
                .contains("application/xml")
                .contains("Pet not found");
    }

    @Test
    void aRecordedServerErrorOnAReadIsReturnedAsTextNotThrown() throws Exception {
        tls.server.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setHeader("Content-Type", "application/json")
                        .setBody(Fixtures.text("inventory-500.json")));

        assertThat(tool("getInventory", "retries", "0").execute(Map.of()))
                .startsWith("HTTP 500")
                .contains("There was an error processing your request");
    }

    // ── G72: the cap says it cut; a saved file cannot escape ─────────────────────────────────

    private static MockResponse big(int bytes, String type) {
        return new MockResponse().setHeader("Content-Type", type).setBody("x".repeat(bytes));
    }

    @Test
    void anAnswerOverTheCapSaysItWasCutAndHowMuchWasShown() throws Exception {
        tls.server.enqueue(big(100_000, "text/plain"));

        String answer = tool("getInventory", "max_bytes", "1k").execute(Map.of());

        assertThat(answer).contains("[cut:").contains("1024").doesNotContain("x".repeat(2000));
    }

    @Test
    void withASaveDirALongAnswerIsKeptWholeAndThePathIsReturned() throws Exception {
        tls.server.enqueue(big(5000, "application/json"));
        Path saved = dir.resolve("out");

        String answer = tool("getInventory", "max_bytes", "1k", "save_dir", "out").execute(Map.of());

        assertThat(answer).contains("5000 bytes").contains("saved to").contains("The start of it");
        try (Stream<Path> files = Files.list(saved)) {
            List<Path> list = files.toList();
            assertThat(list).hasSize(1);
            assertThat(Files.size(list.get(0))).isEqualTo(5000);
            assertThat(list.get(0).getFileName().toString())
                    .matches("Pet_getInventory-[0-9a-f]{16}\\.json");
        }
    }

    @Test
    void theSameAnswerIsSavedUnderTheSameName() throws Exception {
        tls.server.enqueue(big(3000, "text/plain"));
        tls.server.enqueue(big(3000, "text/plain"));
        Tool tool = tool("getInventory", "max_bytes", "1k", "save_dir", "out");

        String first = tool.execute(Map.of());
        String second = tool.execute(Map.of());

        assertThat(second).isEqualTo(first);
        try (Stream<Path> files = Files.list(dir.resolve("out"))) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void theNameComesFromTheContentNeverFromWhatTheServerSends() throws Exception {
        tls.server.enqueue(
                big(3000, "application/octet-stream")
                        .setHeader("Content-Disposition", "attachment; filename=\"../../escape.bin\""));

        tool("getInventory", "save_dir", "out").execute(Map.of());

        assertThat(dir.resolve("escape.bin")).doesNotExist();
        assertThat(dir.getParent().resolve("escape.bin")).doesNotExist();
        try (Stream<Path> files = Files.list(dir.resolve("out"))) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .allMatch(n -> n.matches("Pet_getInventory-[0-9a-f]{16}\\.bin"));
        }
    }

    @Test
    void aBinaryAnswerWithNowhereToKeepItIsRefusedWithAHint() throws Exception {
        Buffer body = new Buffer().write(new byte[] {1, 2, 3, 4});
        tls.server.enqueue(new MockResponse().setHeader("Content-Type", "image/png").setBody(body));

        assertThat(tool("getInventory").execute(Map.of()))
                .startsWith("Error:")
                .contains("not text")
                .contains("save_dir");
    }

    @Test
    void aBinaryAnswerIsSavedWhenThereIsASaveDir() throws Exception {
        Buffer body = new Buffer().write(new byte[] {1, 2, 3, 4});
        tls.server.enqueue(new MockResponse().setHeader("Content-Type", "image/png").setBody(body));

        String answer = tool("getInventory", "save_dir", "out").execute(Map.of());

        assertThat(answer).contains("4 bytes").contains(".png").doesNotContain("The start of it");
    }

    @Test
    void theSaveCeilingAndTheDirectoryAreChecked() {
        assertThat(
                        tls.kind.check(
                                options("spec", "petstore.json", "save_dir", "out", "save_max_bytes", "200m"),
                                dir))
                .contains("save_max_bytes");
        assertThat(tls.kind.check(options("spec", "petstore.json", "save_max_bytes", "1m"), dir))
                .contains("save_dir");
    }

    // ── G73 and G74: a credential, asked for each time, refused once, typed, and never shown ─

    /** A source that hands out the next token each time and counts how often it was asked. */
    private static final class Tokens implements CredentialSource {
        final List<String> asked = new ArrayList<>();
        int rejected = 0;
        private final List<String> tokens;

        Tokens(String... tokens) {
            this.tokens = List.of(tokens);
        }

        @Override
        public String get() {
            String token = tokens.get(Math.min(asked.size(), tokens.size() - 1));
            asked.add(token);
            return token;
        }

        @Override
        public void rejected() {
            rejected++;
        }
    }

    @Test
    void aSourceIsAskedOnEveryRequest() throws Exception {
        tls.server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
        tls.server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
        Tokens tokens = new Tokens("Bearer test-token-one", "Bearer test-token-two");
        Tool tool = withSource("getInventory", tokens);

        tool.execute(Map.of());
        tool.execute(Map.of());

        assertThat(tokens.asked).containsExactly("Bearer test-token-one", "Bearer test-token-two");
        assertThat(tls.next().getHeader("Authorization")).isEqualTo("Bearer test-token-one");
        assertThat(tls.next().getHeader("Authorization")).isEqualTo("Bearer test-token-two");
    }

    @Test
    void aRefusalIsRetriedOnceWithAFreshCredential() throws Exception {
        tls.server.enqueue(new MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer"));
        tls.server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":1}"));
        Tokens tokens = new Tokens("Bearer test-stale-000", "Bearer test-fresh-111");

        String answer = withSource("getInventory", tokens).execute(Map.of());

        assertThat(answer).contains("HTTP 200").contains("{\"ok\":1}");
        assertThat(tokens.rejected).isEqualTo(1);
        assertThat(tls.server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void aSecondRefusalIsATypedFailureCarryingTheRedactedChallenge() throws Exception {
        String challenge =
                "Bearer realm=\"api\", error=\"invalid_token\", resource_metadata=\"https://auth.example.org/.well-known/x\","
                        + " detail=\"test-token-final-222\"";
        tls.server.enqueue(new MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", challenge));
        tls.server.enqueue(new MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", challenge));
        Tokens tokens = new Tokens("Bearer test-token-first-111", "Bearer test-token-final-222");

        String answer = withSource("getInventory", tokens).execute(Map.of());

        assertThat(answer)
                .startsWith("Error: credential_rejected:")
                .contains("resource_metadata")
                .doesNotContain("test-token-final-222")
                .doesNotContain("test-token-first-111");
        assertThat(tokens.rejected).isEqualTo(1);
    }

    @Test
    void aMissingScopeIsATypedFailureWithNoRetry() throws Exception {
        tls.server.enqueue(
                new MockResponse()
                        .setResponseCode(403)
                        .setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"pets:write\""));
        Tokens tokens = new Tokens("Bearer test-token-000");

        String answer = withSource("getInventory", tokens).execute(Map.of());

        assertThat(answer).startsWith("Error: credential_rejected:").contains("insufficient_scope");
        assertThat(tokens.rejected).isZero();
        assertThat(tls.server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void aStaticCredentialThatIsRefusedIsTypedToo() throws Exception {
        tls.server.enqueue(new MockResponse().setResponseCode(401));

        String answer =
                tool("getInventory", "auth_header", "api_key", "auth_value", "test-key-0000").execute(Map.of());

        assertThat(answer).startsWith("Error: credential_rejected:").doesNotContain("test-key-0000");
    }

    @Test
    void aCredentialIsNeverInAnythingTheToolSaysOrAnswersWithOrSavesAsAName() throws Exception {
        tls.server.enqueue(new MockResponse().setResponseCode(401));
        tls.server.enqueue(new MockResponse().setResponseCode(401));
        String canary = "Bearer test-canary-token-9f3a7c";
        Tokens tokens = new Tokens(canary);

        String answer = withSource("getInventory", tokens).execute(Map.of());
        // an echoing server: the token comes back in a body
        tls.server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/plain")
                        .setBody("you sent " + canary + " thanks"));
        String echoed = withSource("getInventory", tokens).execute(Map.of());

        assertThat(answer).doesNotContain("test-canary-token-9f3a7c");
        assertThat(echoed).doesNotContain("test-canary-token-9f3a7c").contains("***");
        assertThat(context.everythingSaid()).doesNotContain("test-canary-token-9f3a7c");
    }

    @Test
    void aCredentialFromASourceIsNotSentOverPlainHttpToAnotherHost() throws Exception {
        Tool tool =
                tls.kind.create(
                        "Pet",
                        options(
                                "spec", "petstore.json", "operation", "getInventory",
                                "base_url", "http://api.example.invalid/v3", "allow_http", "true",
                                "auth_header", "Authorization"),
                        Map.of("auth_value", CredentialSource.of("Bearer test-token-000")),
                        dir,
                        context);

        assertThat(tool.execute(Map.of())).startsWith("Error:").contains("only sent over https");
    }
}
