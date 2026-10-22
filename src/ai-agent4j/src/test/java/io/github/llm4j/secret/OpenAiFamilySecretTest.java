package io.github.llm4j.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.openai.Dialect;
import io.github.llm4j.provider.openai.OpenAiCompatibleProvider;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * R4 for every OpenAI-family dialect: the key is fetched per request, sent only as the dialect's
 * header to a bound host, and never in an error.
 */
class OpenAiFamilySecretTest {

    private static final List<Dialect> DIALECTS =
            List.of(
                    Dialect.OPENAI,
                    Dialect.XAI,
                    Dialect.KIMI,
                    Dialect.QWEN,
                    Dialect.PERPLEXITY_ROUTER,
                    Dialect.GENERIC);
    private static final String CANARY = "sk-canary-0123456789abcdef";

    private MockWebServer server;
    private InMemorySecretStore store;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new InMemorySecretStore();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private OpenAiCompatibleProvider provider(Dialect d, SecretRef key) {
        return new OpenAiCompatibleProvider(
                d,
                LLMConfig.builder()
                        .apiKey(key)
                        .baseUrl(server.url("/v1").toString())
                        .defaultModel("m")
                        .retryPolicy(RetryPolicy.builder().maxRetries(0).build())
                        .build());
    }

    private static void callQuietly(OpenAiCompatibleProvider p) {
        try {
            p.chat(LLMRequest.builder().addUserMessage("hi").build());
        } catch (RuntimeException ignored) {
            // the canned body is not an answer; the request is what is checked
        }
    }

    @Test
    void theKeyIsFetchedForEachRequestAndSentOnlyInTheDialectsHeader() throws Exception {
        for (Dialect d : DIALECTS) {
            store.put("k", CANARY);
            OpenAiCompatibleProvider p = provider(d, SecretRef.of(store, "k"));
            server.enqueue(new MockResponse().setBody("{}"));
            callQuietly(p);
            RecordedRequest first = server.takeRequest();
            assertThat(first.getHeader(d.authHeader()))
                    .as(d.name())
                    .isEqualTo(d.authScheme() + CANARY);
            assertThat(first.getRequestUrl().toString()).as(d.name()).doesNotContain(CANARY);
            assertThat(first.getBody().readUtf8()).as(d.name()).doesNotContain(CANARY);

            store.put("k", CANARY + "-rotated");
            server.enqueue(new MockResponse().setBody("{}"));
            callQuietly(p);
            assertThat(server.takeRequest().getHeader(d.authHeader()))
                    .as(d.name())
                    .endsWith("-rotated");
        }
    }

    @Test
    void aSecretBoundToOtherHostsIsRefusedBeforeAnythingIsSent() {
        for (Dialect d : DIALECTS) {
            store.put("k", CANARY, SecretMetadata.allowing("api.example.com"));
            OpenAiCompatibleProvider p = provider(d, SecretRef.of(store, "k"));
            assertThatThrownBy(() -> p.chat(LLMRequest.builder().addUserMessage("hi").build()))
                    .as(d.name())
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessageNotContaining(CANARY);
        }
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void aSecretBoundToTheVendorsHostsIsAcceptedThere() {
        for (Dialect d : DIALECTS) {
            if (d.hosts().isEmpty()) continue;
            // the preset's hosts are what a secret created for the vendor is offered; the mock host
            // is not among them
            store.put("k", CANARY, SecretMetadata.allowing(d.hosts().toArray(String[]::new)));
            assertThatThrownBy(
                            () ->
                                    provider(d, SecretRef.of(store, "k"))
                                            .chat(
                                                    LLMRequest.builder()
                                                            .addUserMessage("hi")
                                                            .build()))
                    .as(d.name() + " must not send a vendor-bound key to the mock host")
                    .isInstanceOf(AuthenticationException.class);
        }
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void aMissingSecretNamesTheSecretAndAnErrorBodyNeverEchoesTheKey() throws Exception {
        assertThatThrownBy(() -> provider(Dialect.OPENAI, SecretRef.of(store, "not-there")))
                .hasMessageContaining("not-there");
        for (Dialect d : DIALECTS) {
            store.put("k", CANARY);
            server.enqueue(
                    new MockResponse()
                            .setResponseCode(401)
                            .setBody("{\"error\":{\"message\":\"bad key " + CANARY + "\"}}"));
            assertThatThrownBy(
                            () ->
                                    provider(d, SecretRef.of(store, "k"))
                                            .chat(
                                                    LLMRequest.builder()
                                                            .addUserMessage("hi")
                                                            .build()))
                    .as(d.name())
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessageNotContaining(CANARY);
        }
    }
}
