package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.security.SecurityAudit;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.SecretMetadata;
import java.nio.file.Path;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Secret store in Loom (LOOM-*): {@code secret.NAME}, per-request provider keys, host binding, lookup order. */
class SecretsLoomTest {

    static final String ANSWER = "```json\\n{\\\"thought\\\": \\\"t\\\", \\\"final_answer\\\": \\\"hi\\\"}\\n```";
    static final String VALUE1 = "ZZ-first-value-0001";
    static final String VALUE2 = "ZZ-second-value-0002";

    @TempDir
    Path dir;

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

    void reply() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + ANSWER + "\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}"));
    }

    static InMemorySecretStore store(String name, String value, SecretMetadata meta) {
        InMemorySecretStore s = new InMemorySecretStore();
        s.put(name, value.toCharArray(), meta);
        return s;
    }

    String script() {
        return """
                provider Box { use: sarvam  api_key: secret.BOX_KEY  base_url: "%s" }
                agent A { model: "Box/sarvam-m" }
                workflow Main() { delegate "{message}" to A -> out }
                """.formatted(base());
    }

    @Test
    void loom_parse_secretReferenceIsAnOptionValue() {
        LoomScript s = new LoomParser(new Lexer("tool T { use: serpapi  api_key: secret.SERP_KEY }").tokenize()).parseScript();
        ToolDef.OptionValue v = s.getTools().get(0).getOptions().get("api_key");
        assertThat(v.fromSecret()).isTrue();
        assertThat(v.isReference()).isTrue();
        assertThat(v.value()).isEqualTo("SERP_KEY");
        assertThat(v.toString()).isEqualTo("secret.SERP_KEY");
        assertThatThrownBy(() -> new LoomParser(new Lexer("tool T { use: serpapi  api_key: secret.%s }".formatted("A".repeat(65))).tokenize()).parseScript())
                .hasMessageContaining("secret");
    }

    @Test
    void loom_provider_fetchesTheKeyForEachRequest() throws Exception {
        InMemorySecretStore secrets = store("BOX_KEY", VALUE1, SecretMetadata.NONE);
        Harness h = new Harness(dir);
        var e = h.executor("""
                provider Box { use: sarvam  api_key: secret.BOX_KEY  base_url: "%s" }
                agent A { model: "Box/sarvam-m" }
                workflow Main() {
                  delegate "one" to A -> first
                  delegate "two" to A -> second
                }
                """.formatted(base()), new DefaultLLMClientFactory(n -> null, secrets), x -> x.setSecretStore(secrets));
        e.initialize();
        // the secret is rotated while the first request is being served: the second request must carry the new value
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                secrets.put("BOX_KEY", VALUE2.toCharArray(), SecretMetadata.NONE);
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + ANSWER + "\"},\"finish_reason\":\"stop\"}],"
                                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}");
            }
        });
        e.executeWorkflow("Main", Map.of());
        assertThat(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS).getHeader("api-subscription-key")).isEqualTo(VALUE1);
        assertThat(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS).getHeader("api-subscription-key")).isEqualTo(VALUE2);
    }

    @Test
    void loom_provider_keyBoundToAnotherHostIsRefusedBeforeAnyRequest() {
        InMemorySecretStore secrets = store("BOX_KEY", VALUE1, SecretMetadata.allowing("api.example.com"));
        Harness h = new Harness(dir);
        var e = h.executor(script(), new DefaultLLMClientFactory(n -> null, secrets), x -> x.setSecretStore(secrets));
        assertThatThrownBy(e::initialize).isInstanceOf(LoomLoadException.class)
                .hasMessageContaining("may not be sent to")
                .hasMessageNotContaining(VALUE1);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void loom_provider_missingSecretIsALoadError() {
        Harness h = new Harness(dir);
        var e = h.executor(script(), new DefaultLLMClientFactory(n -> null, new InMemorySecretStore()), x -> x.setSecretStore(new InMemorySecretStore()));
        assertThatThrownBy(e::initialize).hasMessageContaining("secret BOX_KEY is not in the secret store");
    }

    @Test
    void loom_provider_withoutAStoreSaysHowToGiveOne() {
        Harness h = new Harness(dir);
        var e = h.executor(script(), new DefaultLLMClientFactory(n -> null), null);
        assertThatThrownBy(e::initialize).hasMessageContaining("--secrets");
    }

    @Test
    void loom_provider_keyOverPlainHttpToARemoteHostIsRefused() {
        InMemorySecretStore secrets = store("BOX_KEY", VALUE1, SecretMetadata.NONE);
        Harness h = new Harness(dir);
        var e = h.executor("""
                provider Box { use: sarvam  api_key: secret.BOX_KEY  base_url: "http://box.example.com" }
                agent A { model: "Box/sarvam-m" }
                workflow Main() { delegate "{message}" to A -> out }
                """, new DefaultLLMClientFactory(n -> null, secrets), x -> x.setSecretStore(secrets));
        assertThatThrownBy(e::initialize).hasMessageContaining("is not https").hasMessageNotContaining(VALUE1);
    }

    @Test
    void loom_builtinModels_lookStoreFirstThenEnvironment() throws Exception {
        InMemorySecretStore secrets = store("SARVAM_API_KEY", VALUE1, SecretMetadata.NONE);
        Map<String, String> env = Map.of("SARVAM_API_KEY", "ENV-value-should-lose", "SARVAM_BASE_URL", base());
        Harness h = new Harness(dir);
        var e = h.executor("""
                agent A { model: "sarvam/sarvam-m" }
                workflow Main() { delegate "{message}" to A -> out }
                """, new DefaultLLMClientFactory(env::get, secrets), x -> x.setSecretStore(secrets));
        e.initialize();
        reply();
        e.executeWorkflow("Main", Map.of("message", "x"));
        assertThat(server.takeRequest().getHeader("api-subscription-key")).isEqualTo(VALUE1);

        // with nothing in the store, the environment still works
        var f = new Harness(dir).executor("""
                agent A { model: "sarvam/sarvam-m" }
                workflow Main() { delegate "{message}" to A -> out }
                """, new DefaultLLMClientFactory(env::get, new InMemorySecretStore()), x -> x.setSecretStore(new InMemorySecretStore()));
        f.initialize();
        reply();
        f.executeWorkflow("Main", Map.of("message", "x"));
        assertThat(server.takeRequest().getHeader("api-subscription-key")).isEqualTo("ENV-value-should-lose");
    }

    @Test
    void loom_tool_secretOptionIsResolvedFromTheStore() throws Exception {
        InMemorySecretStore secrets = store("SERP_KEY", VALUE1, SecretMetadata.NONE);
        Harness h = new Harness(dir);
        var e = h.executor("""
                tool Search { use: serpapi  api_key: secret.SERP_KEY }
                agent A { model: "m" tools: [Search] }
                workflow Main() { delegate "{message}" to A -> out }
                """, x -> x.setSecretStore(secrets));
        e.initialize();
        // an absent secret is a load error that names the secret, never a value
        InMemorySecretStore empty = new InMemorySecretStore();
        assertThatThrownBy(() -> new Harness(dir).executor("""
                tool Search { use: serpapi  api_key: secret.SERP_KEY }
                agent A { model: "m" tools: [Search] }
                """, x -> x.setSecretStore(empty)).initialize()).hasMessageContaining("SERP_KEY");
    }

    @Test
    void loom_audit_flagsACredentialSentToACustomAddress() {
        String envScript = "provider Box { use: sarvam api_key: env.BOX_KEY base_url: \"https://box.example.com\" }";
        String secretScript = "provider Box { use: sarvam api_key: secret.BOX_KEY base_url: \"https://box.example.com\" }";
        var env = SecurityAudit.audit(new LoomParser(new Lexer(envScript).tokenize()).parseScript(), "w.loom");
        var sec = SecurityAudit.audit(new LoomParser(new Lexer(secretScript).tokenize()).parseScript(), "w.loom");
        assertThat(env.findings()).filteredOn(f -> f.rule().equals("LA15")).extracting(f -> f.severity().name()).containsExactly("MEDIUM");
        assertThat(sec.findings()).filteredOn(f -> f.rule().equals("LA15")).extracting(f -> f.severity().name()).containsExactly("LOW");
        var none = SecurityAudit.audit(new LoomParser(new Lexer("provider Box { use: ollama base_url: \"http://localhost:1\" }").tokenize()).parseScript(), "w.loom");
        assertThat(none.findings()).noneMatch(f -> f.rule().equals("LA15"));
    }
}
