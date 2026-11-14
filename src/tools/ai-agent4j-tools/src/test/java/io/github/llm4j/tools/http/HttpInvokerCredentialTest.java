package io.github.llm4j.tools.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.secret.CredentialSource;
import io.github.llm4j.tools.HttpKind;
import io.github.llm4j.tools.support.RecordingEffects;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/** Phase 36b, D8 (G74): {@code http} takes the credential of whoever runs the workflow, asked for on every request. */
class HttpInvokerCredentialTest {

    final RecordingEffects ctx = new RecordingEffects();

    private static final class Tokens implements CredentialSource {
        final List<String> asked = new ArrayList<>();
        int rejected;
        final String[] values;

        Tokens(String... values) {
            this.values = values;
        }

        @Override
        public String get() {
            String v = values[Math.min(asked.size(), values.length - 1)];
            asked.add(v);
            return v;
        }

        @Override
        public void rejected() {
            rejected++;
        }
    }

    private Tool tool(MockWebServer server, CredentialSource source) throws Exception {
        return new HttpKind()
                .create(
                        "Api",
                        Map.of("base_url", "http://localhost:" + server.getPort(), "auth_header", "Authorization", "retries", "0"),
                        Map.of("auth_value", source),
                        Path.of("."),
                        ctx);
    }

    @Test
    void theKindSaysAuthValueMayComeFromTheInvoker() {
        assertThat(new HttpKind().lateBound()).containsExactly("auth_value");
    }

    @Test
    void theSourceIsAskedOnEveryRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain").setBody("a"));
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain").setBody("b"));
            server.start();
            Tokens tokens = new Tokens("Bearer test-one-1111", "Bearer test-two-2222");
            Tool tool = tool(server, tokens);

            tool.execute(Map.of("path", "/x"));
            tool.execute(Map.of("path", "/x"));

            assertThat(tokens.asked).hasSize(2);
            assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer test-one-1111");
            assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer test-two-2222");
        }
    }

    @Test
    void aRefusalIsRetriedOnceWithAFreshOneThenTypedAndNeverShown() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\""));
            server.enqueue(new MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\" x=test-two-2222"));
            server.start();
            Tokens tokens = new Tokens("Bearer test-one-1111", "Bearer test-two-2222");

            String answer = tool(server, tokens).execute(Map.of("path", "/x"));

            assertThat(answer).startsWith("Error: credential_rejected:").contains("invalid_token").doesNotContain("test-two-2222").doesNotContain("test-one-1111");
            assertThat(tokens.rejected).isEqualTo(1);
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void aRefusalFollowedByAcceptanceOfTheNewTokenIsAnAnswer() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain").setBody("fine"));
            server.start();

            assertThat(tool(server, new Tokens("Bearer test-old-0000", "Bearer test-new-1111")).execute(Map.of("path", "/x"))).contains("fine");
        }
    }

    @Test
    void aMissingScopeIsTypedWithNoRetry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(403).setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\""));
            server.start();
            Tokens tokens = new Tokens("Bearer test-one-1111");

            assertThat(tool(server, tokens).execute(Map.of("path", "/x"))).startsWith("Error: credential_rejected:");
            assertThat(tokens.rejected).isZero();
        }
    }

    @Test
    void aTokenFromASourceIsNotSentOverPlainHttpToAnotherHost() throws Exception {
        Tool tool =
                new HttpKind()
                        .create(
                                "Api",
                                Map.of("base_url", "http://api.example.invalid", "allow_http", "true", "auth_header", "Authorization"),
                                Map.of("auth_value", CredentialSource.of("Bearer test-one-1111")),
                                Path.of("."),
                                ctx);

        assertThat(tool.execute(Map.of("path", "/x"))).startsWith("Error:").contains("only sent over https");
    }
}
