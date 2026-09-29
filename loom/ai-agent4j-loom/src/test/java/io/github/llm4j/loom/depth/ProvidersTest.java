package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.cli.CliProbe;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.execution.ProviderSpec;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V3: model providers. */
class ProvidersTest {

    @TempDir
    Path dir;

    MockWebServer server;

    static final String ANSWER = "```json\\n{\\\"thought\\\": \\\"t\\\", \\\"final_answer\\\": \\\"hi\\\"}\\n```";

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

    @Test
    void v3_1_sarvamModels() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + ANSWER + "\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}"));
        Harness h = new Harness(dir);
        Map<String, String> env = Map.of("SARVAM_API_KEY", "sk", "SARVAM_BASE_URL", base());
        var e = h.executor("""
                agent A { model: "sarvam/sarvam-m" }
                workflow Main() { delegate "hello" to A -> out }
                """, new DefaultLLMClientFactory(env::get), null);
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        RecordedRequest r = server.takeRequest();
        assertThat(r.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(r.getHeader("api-subscription-key")).isEqualTo("sk");
        assertThat(r.getBody().readUtf8()).contains("\"model\":\"sarvam-m\"");
        assertThat(e.getContext().getVariable("out")).isEqualTo("hi");
    }

    @Test
    void v3_2_aDeclaredOllamaProvider() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"" + ANSWER + "\"},\"done\":true,\"prompt_eval_count\":3,\"eval_count\":2}"));
        Harness h = new Harness(dir);
        var e = h.executor("""
                provider Box { use: ollama  base_url: "%s" }
                agent A { model: "Box/llama3" }
                workflow Main() { delegate "hello" to A -> out }
                """.formatted(base()), new DefaultLLMClientFactory(n -> null), null);
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        RecordedRequest r = server.takeRequest();
        assertThat(r.getPath()).isEqualTo("/api/chat");
        assertThat(r.getBody().readUtf8()).contains("\"model\":\"llama3\"");
        assertThat(e.getContext().getVariable("out")).isEqualTo("hi");
    }

    @Test
    void declaredProvidersGoThroughTheFactory() {
        List<String> made = new ArrayList<>();
        Harness h = new Harness(dir);
        h.env.put("BOX_KEY", "secret-value");
        LLMClientFactory factory = new LLMClientFactory() {
            @Override
            public io.github.llm4j.LLMClient createClient(String model) {
                made.add("plain " + model);
                return h.client(model);
            }

            @Override
            public io.github.llm4j.LLMClient createClient(ProviderSpec spec, String model) {
                made.add(spec + " " + model + " key=" + spec.apiKey());
                return h.client(model);
            }
        };
        h.executor("""
                provider Box { use: sarvam  api_key: env.BOX_KEY  base_url: "https://box.example" }
                routing R { strategy: fallback  primary: "Box/sarvam-m"  fallbacks: ["gemini-2.5-flash"] }
                agent A { routing: R }
                """, factory, null).initialize();
        assertThat(made).containsExactly(
                "provider Box (sarvam at https://box.example) sarvam-m key=secret-value", "plain gemini-2.5-flash");
        assertThat(new ProviderSpec("Box", "sarvam", null, "secret-value").toString()).doesNotContain("secret-value");
    }

    @Test
    void v3_3_providerDeclarationsAreChecked() {
        Harness h = new Harness(dir);
        assertThatThrownBy(() -> h.ready("""
                provider P { use: gemini api_key: "literal" }
                provider Q { use: sarvam api_key: env.MISSING }
                provider R { use: openai }
                provider gemini { use: ollama }
                provider S { use: ollama colour: "x" }
                provider S { use: ollama }
                provider T { use: gemini }
                """)).isInstanceOfSatisfying(LoomLoadException.class, e -> assertThat(e.getMessage())
                .contains("line 1: provider P: api_key must come from the environment")
                .contains("line 2: provider Q: environment variable MISSING is not set")
                .contains("line 3: provider R: unknown use: openai; use one of gemini, anthropic, ollama, sarvam")
                .contains("line 4: provider gemini: the name gemini is reserved")
                .contains("line 5: provider S: unknown option colour")
                .contains("line 6: provider S: declared twice")
                .contains("line 7: provider T: gemini needs api_key: env.<NAME>"));
        assertThatThrownBy(() -> h.ready("provider P { base_url: \"x\" }")).hasMessageContaining("needs use:");
    }

    @Test
    void v3_4_unknownModelsFailWithTheDefaultFactoryOnly() throws Exception {
        String script = """
                agent A { model: "gpt-5" }
                agent B { model: "gemini-2.5-flash" }
                agent C { model: "sarvam/sarvam-m" }
                agent D { }
                routing R { primary: "Box/" fallbacks: ["nope"] }
                provider Box { use: ollama }
                """;
        assertThatThrownBy(() -> new Harness(dir).executor(script, new DefaultLLMClientFactory(n -> null), null).initialize())
                .isInstanceOfSatisfying(LoomLoadException.class, e -> assertThat(e.getMessage())
                        .contains("agent A: unknown model \"gpt-5\"")
                        .contains("agent B: model gemini-2.5-flash needs GEMINI_API_KEY")
                        .contains("agent C: model sarvam/sarvam-m needs SARVAM_API_KEY")
                        .contains("agent D: needs model:")
                        .contains("routing R: model Box/ names no model after the provider")
                        .contains("routing R: unknown model \"nope\""));
        // a host factory is trusted with its own names
        new Harness(dir).ready("agent A { model: \"studio\" }");

        // weave check uses the environment's factory to check names, and never creates a client
        Path f = dir.resolve("s.loom");
        Files.writeString(f, "agent A { model: \"gpt-5\" }\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(CliProbe.check(f.toFile(), new PrintStream(out, true), n -> null, new DefaultLLMClientFactory(n -> null))).isEqualTo(2);
        assertThat(out.toString()).contains("line 1: agent A: unknown model \"gpt-5\"");
        AtomicInteger created = new AtomicInteger();
        Files.writeString(f, "agent A { model: \"gemini-2.5-flash\" }\n");
        assertThat(CliProbe.check(f.toFile(), new PrintStream(out, true), Map.of("GEMINI_API_KEY", "k")::get,
                new DefaultLLMClientFactory(Map.of("GEMINI_API_KEY", "k")::get) {
                    @Override
                    public io.github.llm4j.LLMClient createClient(String model) {
                        created.incrementAndGet();
                        return super.createClient(model);
                    }
                })).isZero();
        assertThat(created).hasValue(0);
    }

    @Test
    void theDefaultFactoryResolvesItsPatterns() {
        DefaultLLMClientFactory f = new DefaultLLMClientFactory(Map.of("GEMINI_API_KEY", "g", "SARVAM_API_KEY", "s")::get);
        assertThat(f.problem("gemini-2.5-flash")).isNull();
        assertThat(f.problem("ollama/llama3")).isNull();
        assertThat(f.problem("gemma3")).isNull();
        assertThat(f.problem("sarvam/sarvam-m")).isNull();
        assertThat(f.problem("")).isEqualTo("no model given");
        assertThat(f.createClient("gemini-2.5-flash")).isNotNull();
        assertThat(f.createClient("ollama/llama3")).isNotNull();
        assertThat(f.createClient("sarvam/sarvam-m")).isNotNull();
        assertThatThrownBy(() -> f.createClient("gpt-5")).hasMessageContaining("unknown model");
        DefaultLLMClientFactory none = new DefaultLLMClientFactory(n -> null);
        assertThatThrownBy(() -> none.createClient("sarvam/x")).hasMessageContaining("SARVAM_API_KEY");
        assertThatThrownBy(() -> DefaultLLMClientFactory.forProvider(new ProviderSpec("X", "openai", null, "k"), "m"))
                .hasMessageContaining("unknown provider kind");
        assertThat(DefaultLLMClientFactory.forProvider(new ProviderSpec("G", "gemini", "https://g.example", "k"), "gemini-x")).isNotNull();
    }

    static final String CLAUDE_ANSWER = "```json\\n{\\\"thought\\\": \\\"t\\\", \\\"final_answer\\\": \\\"hi\\\"}\\n```";

    static MockResponse claude(String text) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],\"stop_reason\":\"end_turn\","
                        + "\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}");
    }

    @Test
    void anthropicModelsResolveByNameAndThroughDeclarations() throws Exception {
        server.enqueue(claude(CLAUDE_ANSWER));
        Map<String, String> env = Map.of("ANTHROPIC_API_KEY", "sk-ant-test", "ANTHROPIC_BASE_URL", base());
        Harness h = new Harness(dir);
        var e = h.executor("""
                agent A { model: "anthropic/claude-opus-5-5" }
                workflow Main() { delegate "hello" to A -> out }
                """, new DefaultLLMClientFactory(env::get), null);
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        RecordedRequest r = server.takeRequest();
        assertThat(r.getPath()).isEqualTo("/v1/messages");
        assertThat(r.getHeader("x-api-key")).isEqualTo("sk-ant-test");
        assertThat(r.getHeader("anthropic-version")).isEqualTo("2023-06-01");
        String body = r.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"claude-opus-5-5\"").contains("\"max_tokens\":16000")
                .doesNotContain("temperature"); // the agent's default temperature is left out for this model
        assertThat(e.getContext().getVariable("out")).isEqualTo("hi");

        server.enqueue(claude(CLAUDE_ANSWER));
        Harness h2 = new Harness(dir);
        h2.env.put("TEAM_KEY", "sk-ant-team");
        var e2 = h2.executor("""
                provider Team { use: anthropic  api_key: env.TEAM_KEY  base_url: "%s" }
                agent B { model: "Team/claude-haiku-4-5" }
                workflow Main() { delegate "hello" to B -> out }
                """.formatted(base()), new DefaultLLMClientFactory(n -> null), null);
        e2.initialize();
        e2.executeWorkflow("Main", Map.of());
        RecordedRequest r2 = server.takeRequest();
        assertThat(r2.getHeader("x-api-key")).isEqualTo("sk-ant-team");
        assertThat(r2.getBody().readUtf8()).contains("\"model\":\"claude-haiku-4-5\"").contains("\"temperature\"");
    }

    @Test
    void anthropicNeedsItsKeyAndItsNameIsReserved() {
        DefaultLLMClientFactory none = new DefaultLLMClientFactory(n -> null);
        assertThat(none.problem("claude-opus-5-5")).isEqualTo("model claude-opus-5-5 needs ANTHROPIC_API_KEY in the environment");
        assertThat(none.problem("anthropic/claude-haiku-4-5")).contains("ANTHROPIC_API_KEY");
        assertThat(new DefaultLLMClientFactory(Map.of("ANTHROPIC_API_KEY", "k")::get).problem("claude-sonnet-5-5")).isNull();
        assertThatThrownBy(() -> none.createClient("claude-opus-5-5")).hasMessageContaining("ANTHROPIC_API_KEY");
        assertThatThrownBy(() -> new Harness(dir).ready("provider anthropic { use: anthropic api_key: env.K }"))
                .hasMessageContaining("the name anthropic is reserved");
        assertThatThrownBy(() -> new Harness(dir).executor("agent A { model: \"claude-opus-5-5\" }", none, null).initialize())
                .isInstanceOf(LoomLoadException.class).hasMessageContaining("needs ANTHROPIC_API_KEY");
    }
}
