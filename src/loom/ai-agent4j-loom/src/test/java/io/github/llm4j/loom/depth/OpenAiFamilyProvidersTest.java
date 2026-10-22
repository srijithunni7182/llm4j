package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.execution.ProviderSpec;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.SecretMetadata;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 8c: the OpenAI-family providers in Loom: model names, keys, declared providers and their
 * checks.
 */
class OpenAiFamilyProvidersTest {

    @TempDir Path dir;

    MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    String base() {
        return server.url("").toString().replaceAll("/$", "");
    }

    private static MockResponse chat() {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                                + ProvidersTest.ANSWER
                                + "\"},\"finish_reason\":\"stop\"}],"
                                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}");
    }

    private static MockResponse responses() {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                        "{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\""
                                + ProvidersTest.ANSWER
                                + "\"}]}],\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}");
    }

    private RecordedRequest run(String script, Map<String, String> env, MockResponse reply)
            throws Exception {
        server.enqueue(reply);
        Harness h = new Harness(dir);
        var e = h.executor(script, new DefaultLLMClientFactory(env::get), null);
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(e.getContext().getVariable("out")).isEqualTo("hi");
        return server.takeRequest();
    }

    private static String agentScript(String model) {
        return "agent A { model: \""
                + model
                + "\" }\nworkflow Main() { delegate \"hello\" to A -> out }\n";
    }

    @Test
    void openAiByPrefixAndByBareName() throws Exception {
        for (String model : new String[] {"openai/gpt-4.1", "gpt-4.1"}) {
            Map<String, String> env =
                    Map.of("OPENAI_API_KEY", "sk-open", "OPENAI_BASE_URL", base() + "/v1");
            RecordedRequest r = run(agentScript(model), env, chat());
            assertThat(r.getPath()).isEqualTo("/v1/chat/completions");
            assertThat(r.getHeader("Authorization")).isEqualTo("Bearer sk-open");
            assertThat(r.getBody().readUtf8()).contains("\"model\":\"gpt-4.1\"");
        }
    }

    @Test
    void xaiKimiAndQwenEachUseTheirKeyAndBaseUrl() throws Exception {
        Map<String, String[]> cases =
                Map.of(
                        "xai/grok-3", new String[] {"XAI_API_KEY", "XAI_BASE_URL", "grok-3"},
                        "grok-3", new String[] {"XAI_API_KEY", "XAI_BASE_URL", "grok-3"},
                        "kimi/kimi-k2.6",
                                new String[] {"MOONSHOT_API_KEY", "MOONSHOT_BASE_URL", "kimi-k2.6"},
                        "qwen/qwen-plus",
                                new String[] {
                                    "DASHSCOPE_API_KEY", "DASHSCOPE_BASE_URL", "qwen-plus"
                                });
        for (var c : cases.entrySet()) {
            Map<String, String> env =
                    Map.of(c.getValue()[0], "key-123456", c.getValue()[1], base() + "/v1");
            RecordedRequest r = run(agentScript(c.getKey()), env, chat());
            assertThat(r.getPath()).as(c.getKey()).isEqualTo("/v1/chat/completions");
            assertThat(r.getHeader("Authorization")).as(c.getKey()).isEqualTo("Bearer key-123456");
            assertThat(r.getBody().readUtf8())
                    .as(c.getKey())
                    .contains("\"model\":\"" + c.getValue()[2] + "\"");
        }
    }

    @Test
    void perplexityIsTheAgentApiAndTheSlugIsPassedThroughUntouched() throws Exception {
        Map<String, String> env =
                Map.of("PERPLEXITY_API_KEY", "pplx-key-1", "PERPLEXITY_BASE_URL", base() + "/v1");
        RecordedRequest r = run(agentScript("perplexity/openai/gpt-4.1"), env, responses());
        assertThat(r.getPath()).isEqualTo("/v1/agent");
        assertThat(r.getHeader("Authorization")).isEqualTo("Bearer pplx-key-1");
        String body = r.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"openai/gpt-4.1\"").doesNotContain("\"tools\"");
    }

    @Test
    void aDeclaredOpenAiCompatibleProviderWithAHeaderAndFixedQueryParameters() throws Exception {
        Harness h = new Harness(dir);
        h.env.put("BOX_KEY", "box-key-123");
        server.enqueue(chat());
        var e =
                h.executor(
                        """
                provider Box { use: openai_compatible  base_url: "%s/v1"  api_key: env.BOX_KEY  header: "api-key"  query: "api-version=2025-01-01" }
                agent A { model: "Box/llama-local" }
                workflow Main() { delegate "hello" to A -> out }
                """
                                .formatted(base()),
                        new DefaultLLMClientFactory(n -> null),
                        null);
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        RecordedRequest r = server.takeRequest();
        assertThat(r.getPath()).isEqualTo("/v1/chat/completions?api-version=2025-01-01");
        assertThat(r.getHeader("api-key")).isEqualTo("box-key-123");
        assertThat(r.getBody().readUtf8()).contains("\"model\":\"llama-local\"");
        assertThat(e.getContext().getVariable("out")).isEqualTo("hi");
    }

    @Test
    void anOpenAiProviderCanUseTheResponsesApi() throws Exception {
        Harness h = new Harness(dir);
        h.env.put("OAI_KEY", "oai-key-123");
        server.enqueue(responses());
        var e =
                h.executor(
                        """
                provider Oai { use: openai  base_url: "%s/v1"  api_key: env.OAI_KEY  api: responses }
                agent A { model: "Oai/gpt-4.1" }
                workflow Main() { delegate "hello" to A -> out }
                """
                                .formatted(base()),
                        new DefaultLLMClientFactory(n -> null),
                        null);
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/responses");
        assertThat(e.getContext().getVariable("out")).isEqualTo("hi");
    }

    @Test
    void anXaiProviderCanUseTheResponsesApiToo() throws Exception {
        Harness h = new Harness(dir);
        h.env.put("XAI_TEST_KEY", "xai-key-123");
        server.enqueue(responses());
        var e =
                h.executor(
                        """
                provider Grok { use: xai  base_url: "%s/v1"  api_key: env.XAI_TEST_KEY  api: responses }
                agent A { model: "Grok/grok-4.3" }
                workflow Main() { delegate "hello" to A -> out }
                """
                                .formatted(base()),
                        new DefaultLLMClientFactory(n -> null),
                        null);
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/responses");
        assertThat(e.getContext().getVariable("out")).isEqualTo("hi");
    }

    @Test
    void theApiOptionIsRefusedForTheOtherKinds() {
        Harness h = new Harness(dir);
        assertThatThrownBy(
                        () ->
                                h.ready(
                                        """
                provider K { use: kimi  api_key: env.NOPE  api: responses }
                """))
                .hasMessageContaining(
                        "line 1: provider K: the option api is only for use: openai or xai");
    }

    @Test
    void declarationsAreChecked() {
        Harness h = new Harness(dir);
        assertThatThrownBy(
                        () ->
                                h.ready(
                                        """
                provider A { use: openai_compatible }
                provider B { use: openai_compatible  base_url: "https://x.example/v1"  proxy: "no" }
                provider C { use: openai  api_key: env.NOPE  api: soap }
                provider D { use: kimi  api_key: env.NOPE  header: "x" }
                provider E { use: openai_compatible  base_url: "https://x.example/v1"  query: "no-equals" }
                provider F { use: openai_compatible  base_url: "http://remote.example/v1"  api_key: env.NOPE }
                provider G { use: nonesuch }
                provider openai { use: ollama }
                """))
                .isInstanceOfSatisfying(
                        LoomLoadException.class,
                        e ->
                                assertThat(e.getMessage())
                                        .contains(
                                                "line 1: provider A: openai_compatible needs base_url")
                                        .contains(
                                                "line 2: provider B: unknown option proxy; use base_url, api_key, header, query")
                                        .contains(
                                                "line 3: provider C: api must be chat or responses")
                                        .contains(
                                                "line 4: provider D: the option header is only for use: openai_compatible")
                                        .contains(
                                                "line 5: provider E: query must be name=value pairs")
                                        .contains(
                                                "line 6: provider F: base_url http://remote.example/v1 is not https")
                                        .contains(
                                                "line 7: provider G: unknown use: nonesuch; use one of gemini, anthropic, ollama, sarvam, jev, openai, xai, kimi, qwen, perplexity, perplexity_router, openai_compatible")
                                        .contains(
                                                "line 8: provider openai: the name openai is reserved"));
    }

    @Test
    void aLocalServerNeedsNoKey() {
        new Harness(dir)
                .ready(
                        """
                provider Local { use: openai_compatible  base_url: "http://localhost:8000/v1" }
                agent A { model: "Local/qwen3" }
                """);
    }

    @Test
    void theResolverHandlesTheNewNamesAndKeepsTheAmbiguousOnesUnresolved() {
        DefaultLLMClientFactory none = new DefaultLLMClientFactory(n -> null);
        assertThat(none.problem("gpt-5-mini"))
                .isEqualTo(
                        "model gpt-5-mini needs OPENAI_API_KEY in the environment or the secret store");
        assertThat(none.problem("grok-4")).contains("XAI_API_KEY");
        assertThat(none.problem("kimi/kimi-k3")).contains("MOONSHOT_API_KEY");
        assertThat(none.problem("qwen/qwen3-max")).contains("DASHSCOPE_API_KEY");
        assertThat(none.problem("perplexity/openai/gpt-5.6-sol")).contains("PERPLEXITY_API_KEY");
        // Perplexity slugs name other vendors' models; the prefix decides, not the contents
        assertThat(none.problem("perplexity/google/gemini-3.7-flash"))
                .contains("PERPLEXITY_API_KEY");
        // qwen3 usually means a local Ollama model, so it is not guessed
        assertThat(none.problem("qwen3")).contains("unknown model \"qwen3\"");
        assertThat(none.problem("qwen3"))
                .contains(
                        "openai/<model>",
                        "xai/<model>",
                        "kimi/<model>",
                        "qwen/<model>",
                        "perplexity/<creator>/<model>");
        assertThat(none.problem("gpt-oss")).contains("unknown model");
        DefaultLLMClientFactory keyed =
                new DefaultLLMClientFactory(
                        Map.of(
                                        "OPENAI_API_KEY",
                                        "k",
                                        "XAI_API_KEY",
                                        "k",
                                        "MOONSHOT_API_KEY",
                                        "k",
                                        "DASHSCOPE_API_KEY",
                                        "k",
                                        "PERPLEXITY_API_KEY",
                                        "k")
                                ::get);
        for (String m :
                new String[] {
                    "gpt-5-mini",
                    "o3-mini",
                    "grok-4",
                    "kimi-k3",
                    "qwen/qwen3-max",
                    "perplexity/preset:medium",
                    "chatgpt-4o-latest"
                }) {
            assertThat(keyed.problem(m)).as(m).isNull();
            assertThat(keyed.createClient(m)).as(m).isNotNull();
        }
    }

    @Test
    void aStoredKeyKeepsItsHostBindingForTheVendor() {
        InMemorySecretStore store = new InMemorySecretStore();
        store.put("OPENAI_API_KEY", "sk-bound-123456", SecretMetadata.allowing("api.openai.com"));
        Map<String, String> env = new HashMap<>();
        env.put("OPENAI_BASE_URL", base() + "/v1");
        DefaultLLMClientFactory f = new DefaultLLMClientFactory(env::get, store);
        assertThat(f.problem("gpt-4.1")).isNull();
        var client = f.createClient("gpt-4.1");
        assertThatThrownBy(() -> client.chat(LLMRequest.builder().addUserMessage("hi").build()))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageNotContaining("sk-bound-123456");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void providerSpecKeepsItsOldConstructorsAndNeverShowsTheKey() {
        ProviderSpec spec =
                new ProviderSpec("Box", "openai_compatible", "https://b.example", "secret-value");
        assertThat(spec.options()).isEmpty();
        assertThat(spec.toString()).doesNotContain("secret-value");
        assertThat(ProviderSpec.KINDS)
                .contains(
                        "gemini",
                        "jev",
                        "openai",
                        "xai",
                        "kimi",
                        "qwen",
                        "perplexity",
                        "perplexity_router",
                        "openai_compatible");
    }
}
