package io.github.llm4j.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.DescribableProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The code samples in wiki/OpenAI-Family-Providers.md, compiled and run against a local server. Change a sample here and in the page together. */
class ProvidersDocsExamplesTest {

    private static final String CHAT = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Paris.\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2,\"total_tokens\":11}}";
    private static final String RESPONSES = "{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Paris.\"}]}],\"usage\":{\"input_tokens\":9,\"output_tokens\":2,\"total_tokens\":11}}";

    private MockWebServer server;
    private final String apiKey = "key-for-the-examples";

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private String serverUrl(String path) {
        return server.url(path).toString().replaceAll("/$", "");
    }

    private void reply(String body) {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
    }

    @Test
    void openAi() throws Exception {
        reply(CHAT);
        LLMConfig base = LLMConfig.builder().apiKey(apiKey).baseUrl(serverUrl("/v1")).build();
        // --- sample: openai
        LLMConfig cfg = LLMConfig.builder().apiKey(apiKey).defaultModel("gpt-5-mini").build();
        LLMClient openai = new DefaultLLMClient(OpenAiCompatibleProvider.openai(cfg));
        // --- end sample
        assertThat(openai).isNotNull();
        LLMClient local = new DefaultLLMClient(OpenAiCompatibleProvider.openai(base));
        assertThat(local.chat(LLMRequest.builder().addUserMessage("Capital of France?").model("gpt-5-mini").build()).getContent()).isEqualTo("Paris.");
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer " + apiKey);
    }

    @Test
    void theOtherPresets() {
        LLMConfig cfg2 = LLMConfig.builder().apiKey(apiKey).defaultModel("grok-4").build();
        LLMConfig cfg3 = LLMConfig.builder().apiKey(apiKey).defaultModel("kimi-k3").build();
        LLMConfig cfg4 = LLMConfig.builder().apiKey(apiKey).defaultModel("qwen3.8-max").build();
        // --- sample: presets
        LLMClient grok = new DefaultLLMClient(OpenAiCompatibleProvider.xai(cfg2));
        LLMClient kimi = new DefaultLLMClient(OpenAiCompatibleProvider.kimi(cfg3));     // preview
        LLMClient qwen = new DefaultLLMClient(OpenAiCompatibleProvider.qwen(cfg4));     // preview, DashScope
        // --- end sample
        assertThat(List.of(grok, kimi, qwen)).doesNotContainNull();
    }

    @Test
    void perplexity() throws Exception {
        reply(RESPONSES);
        // --- sample: perplexity
        LLMConfig cfg = LLMConfig.builder().apiKey(apiKey).defaultModel("openai/gpt-5.6-sol").build();
        LLMClient perplexity = new DefaultLLMClient(OpenAiResponsesProvider.perplexity(cfg));
        // --- end sample
        assertThat(perplexity).isNotNull();
        LLMClient local = new DefaultLLMClient(OpenAiResponsesProvider.perplexity(LLMConfig.builder().apiKey(apiKey).baseUrl(serverUrl("/v1")).build()));
        LLMResponse r = local.chat(LLMRequest.builder().addUserMessage("Capital of France?").model("preset:medium").build());
        assertThat(r.getContent()).isEqualTo("Paris.");
        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/v1/agent");
        assertThat(sent.getBody().readUtf8()).contains("\"preset\":\"medium\"").doesNotContain("\"tools\"");
    }

    @Test
    void anyOpenAiStyleServer() throws Exception {
        reply(CHAT);
        String gatewayUrl = serverUrl("/v1");
        // --- sample: generic
        Dialect gateway = Dialect.GENERIC
                .withAuthHeader("api-key", "")                              // the header name some gateways want
                .withQueryParams(Map.of("api-version", "2025-01-01"));      // fixed query parameters
        LLMConfig cfg = LLMConfig.builder().baseUrl(gatewayUrl).apiKey(apiKey).defaultModel("llama-3.3-70b").build();
        LLMClient any = new DefaultLLMClient(OpenAiCompatibleProvider.of(gateway, cfg));
        // --- end sample
        assertThat(any.chat(LLMRequest.builder().addUserMessage("hi").build()).getContent()).isEqualTo("Paris.");
        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/v1/chat/completions?api-version=2025-01-01");
        assertThat(sent.getHeader("api-key")).isEqualTo(apiKey);
    }

    @Test
    void aNewVendorIsAConstant() throws Exception {
        reply(CHAT);
        String acmeUrl = serverUrl("/api");
        // --- sample: dialect
        Dialect acme = Dialect.builder("acme")
                .defaultBaseUrl("https://llm.acme.example/api")
                .maxTokensField("max_new_tokens")               // how this vendor names the output limit
                .keyEnv("ACME_API_KEY").hosts(List.of("llm.acme.example"))
                .extraRequest(Map.of("safe_mode", true))         // sent with every request
                .build();
        // --- end sample
        LLMConfig cfg = LLMConfig.builder().apiKey(apiKey).baseUrl(acmeUrl).defaultModel("a-1").build();
        LLMClient client = new DefaultLLMClient(OpenAiCompatibleProvider.of(acme, cfg));
        client.chat(LLMRequest.builder().addUserMessage("hi").maxTokens(5).build());
        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"max_new_tokens\":5").contains("\"safe_mode\":true");
    }

    @Test
    void reasoningModelsAndTheEscapeHatch() throws Exception {
        reply(CHAT);
        LLMClient client = new DefaultLLMClient(OpenAiCompatibleProvider.of(Dialect.OPENAI, LLMConfig.builder().apiKey(apiKey).baseUrl(serverUrl("/v1")).defaultModel("gpt-5-mini").build()));
        // --- sample: reasoning
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Plan the migration.")
                .temperature(0.2)                                 // left out for a reasoning model, which rejects it
                .addParameter("reasoning_effort", "low")          // anything else the vendor accepts goes through
                .build();
        LLMResponse answer = client.chat(request);
        // --- end sample
        assertThat(answer.getContent()).isEqualTo("Paris.");
        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).doesNotContain("temperature").contains("\"reasoning_effort\":\"low\"");
    }

    @Test
    void listingModels() throws Exception {
        String list = "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-5-mini\"},{\"id\":\"gpt-5\"}]}";
        reply(list);
        reply(list);
        LLMConfig cfg = LLMConfig.builder().apiKey(apiKey).baseUrl(serverUrl("/v1")).build();
        // --- sample: list-models
        DescribableProvider provider = OpenAiCompatibleProvider.openai(cfg);
        String[] ids = provider.listModels();               // GET /v1/models: ids, in the vendor's order
        String first = provider.getFirstAvailableModel();   // the first id, or null when there are none
        // --- end sample
        assertThat(ids).containsExactly("gpt-5-mini", "gpt-5");
        assertThat(first).isEqualTo("gpt-5-mini");
    }

    @Test
    void theVendorsRegistry() {
        // --- sample: vendors
        Vendor.ofModel("gpt-5-mini");                          // OPENAI, sends "gpt-5-mini"
        Vendor.ofModel("perplexity/openai/gpt-5.6-sol");       // PERPLEXITY, sends "openai/gpt-5.6-sol"
        Vendor.ofModel("qwen3");                               // empty: it usually means a local Ollama model
        // --- end sample
        assertThat(Vendor.ofModel("gpt-5-mini").orElseThrow().vendor()).isEqualTo(Vendor.OPENAI);
        assertThat(Vendor.ofModel("perplexity/openai/gpt-5.6-sol").orElseThrow().model()).isEqualTo("openai/gpt-5.6-sol");
        assertThat(Vendor.ofModel("qwen3")).isEmpty();
    }

    @Test
    void thePageShowsExactlyTheSamplesThatAreTested() throws Exception {
        String source = Files.readString(Path.of("src/test/java/io/github/llm4j/provider/openai/ProvidersDocsExamplesTest.java"));
        String page = Files.readString(Path.of("wiki/OpenAI-Family-Providers.md"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("// --- sample: ([^\\n]+)\\n(.*?)\\n\\s*// --- end sample", java.util.regex.Pattern.DOTALL).matcher(source);
        int found = 0;
        while (m.find()) {
            found++;
            if (m.group(1).equals("perplexity")) continue; // shown on the Responses page, checked below
            String sample = m.group(2).stripIndent().strip();
            assertThat(page).as("the page shows the '" + m.group(1) + "' sample").contains(sample);
        }
        assertThat(found).isEqualTo(8);
    }

    @Test
    void theResponsesPageShowsThePerplexitySample() throws Exception {
        String source = Files.readString(Path.of("src/test/java/io/github/llm4j/provider/openai/ProvidersDocsExamplesTest.java"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("// --- sample: perplexity\\n(.*?)\\n\\s*// --- end sample", java.util.regex.Pattern.DOTALL).matcher(source);
        assertThat(m.find()).isTrue();
        assertThat(Files.readString(Path.of("wiki/Responses-Schema-and-Perplexity.md"))).contains(m.group(1).stripIndent().strip());
    }
}
