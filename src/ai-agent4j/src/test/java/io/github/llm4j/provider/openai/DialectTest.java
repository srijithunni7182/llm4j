package io.github.llm4j.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The data in {@link Dialect} and {@link ModelTraits} (R2, R3, A2): a vendor is a constant, and an
 * unknown model degrades safely.
 */
class DialectTest {

    @Test
    void everyPresetNamesItsKeyBaseUrlAndHosts() {
        for (Dialect d :
                List.of(Dialect.OPENAI, Dialect.XAI, Dialect.KIMI, Dialect.QWEN, Dialect.SARVAM)) {
            assertThat(d.name()).isNotBlank();
            assertThat(d.defaultBaseUrl()).startsWith("https://");
            assertThat(d.keyEnv()).endsWith("_API_KEY");
            assertThat(d.baseUrlEnv()).endsWith("_BASE_URL");
            assertThat(d.hosts()).isNotEmpty();
            assertThat(d.defaultBaseUrl()).contains(d.hosts().get(0));
        }
        assertThat(Dialect.OPENAI.hosts()).containsExactly("api.openai.com");
        assertThat(Dialect.XAI.keyEnv()).isEqualTo("XAI_API_KEY");
        assertThat(Dialect.KIMI.keyEnv()).isEqualTo("MOONSHOT_API_KEY");
        assertThat(Dialect.QWEN.keyEnv()).isEqualTo("DASHSCOPE_API_KEY");
    }

    @ParameterizedTest
    @CsvSource({
        "gpt-4.1,true",
        "gpt-4o,true",
        "gpt-4o-mini,true",
        "chatgpt-4o-latest,true",
        "gpt-5-chat-latest,true",
        "gpt-5,false",
        "gpt-5-mini,false",
        "gpt-5.1,false",
        "o1,false",
        "o3-mini,false",
        "o4-mini,false",
        "gpt-7-turbo,false",
        "ft:gpt-4.1:acme::abc,true"
    })
    void openAiReasoningModelsRejectSamplingSettings(String model, boolean acceptsTemperature) {
        assertThat(ModelTraits.openai(model).acceptsTemperature()).isEqualTo(acceptsTemperature);
    }

    @Test
    void anUnrecognisedModelAtAnyVendorGetsTheSafeDefaults() {
        for (Dialect d :
                List.of(
                        Dialect.OPENAI,
                        Dialect.XAI,
                        Dialect.KIMI,
                        Dialect.QWEN,
                        Dialect.SARVAM,
                        Dialect.GENERIC)) {
            ModelTraits t = d.traits("some-model-nobody-has-heard-of");
            assertThat(t).isNotNull();
            assertThat(t.reasoningOn()).isEmpty();
            assertThat(t.reasoningOff()).isEmpty();
        }
        assertThat(Dialect.GENERIC.traits("whatever")).isEqualTo(ModelTraits.DEFAULT);
    }

    @Test
    void xaiReasoningModelsRejectStopButTakeTemperature() {
        assertThat(ModelTraits.xai("grok-4").acceptsStop()).isFalse();
        assertThat(ModelTraits.xai("grok-4").acceptsTemperature()).isTrue();
        assertThat(ModelTraits.xai("grok-3-mini").acceptsStop()).isFalse();
        assertThat(ModelTraits.xai("grok-3").acceptsStop()).isTrue();
        assertThat(ModelTraits.xai("grok-4-fast-non-reasoning").acceptsStop()).isTrue();
    }

    @Test
    void kimiNewerModelsFixTheirSamplingAndK26HasAThinkingSwitch() {
        assertThat(ModelTraits.kimi("kimi-k3").acceptsTemperature()).isFalse();
        assertThat(ModelTraits.kimi("kimi-k2.6").acceptsTopP()).isFalse();
        assertThat(ModelTraits.kimi("kimi-k2-0905-preview").acceptsTemperature()).isTrue();
        assertThat(ModelTraits.kimi("moonshot-v1-8k").acceptsTemperature()).isTrue();
        assertThat(ModelTraits.kimi("kimi-k2.6").reasoningOn()).containsKey("thinking");
        assertThat(ModelTraits.kimi("kimi-k2.6").reasoningOff()).containsKey("thinking");
        assertThat(ModelTraits.kimi("kimi-k3").reasoningOff()).isEmpty();
        assertThat(ModelTraits.kimi("kimi-k2.7-code").reasoningOff()).isEmpty();
    }

    @Test
    void sarvamCallsTheV2PathsAndSendsTheSubscriptionKeyBare() {
        assertThat(Dialect.SARVAM.defaultBaseUrl()).isEqualTo("https://api.sarvam.ai");
        assertThat(Dialect.SARVAM.chatPath()).isEqualTo("/v2/chat/completions");
        assertThat(Dialect.SARVAM.modelsPath()).isEqualTo("/v2/models");
        assertThat(Dialect.SARVAM.authHeader()).isEqualTo("api-subscription-key");
        assertThat(Dialect.SARVAM.authScheme()).isEmpty();
        assertThat(Dialect.SARVAM.maxTokensField()).isEqualTo("max_tokens");
        assertThat(Dialect.SARVAM.fallbackModel()).isEqualTo("sarvam-105b");
    }

    @ParameterizedTest
    @CsvSource({
        "sarvam-105b,none,",
        "deepseekv4.1-flash-1017,none,",
        "deepseekv4.1-flash-1017-interactive,none,",
        "deepseekv4-pro,none,",
        "qwen38-27b,none,",
        "gemma4,,low",
        "glm5.3,,",
        "glm5.3-flash,,",
        "kimi-k3.1-pro,,",
        "sarvam-105b-conversations,,",
        "SARVAM-105B,none,"
    })
    void sarvamModelsSwitchThinkingTheirOwnWay(String model, String off, String on) {
        ModelTraits t = ModelTraits.sarvam(model);
        if (off == null) assertThat(t.reasoningOff()).isEmpty();
        else assertThat(t.reasoningOff()).containsExactly(Map.entry("reasoning_effort", off));
        if (on == null) assertThat(t.reasoningOn()).isEmpty();
        else assertThat(t.reasoningOn()).containsExactly(Map.entry("reasoning_effort", on));
        assertThat(t.acceptsTemperature()).isTrue();
        assertThat(t.acceptsStop()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "sarvam-105b,true,false",
        "glm5.3,true,false",
        "glm5.3-flash,true,true",
        "gemma4,true,true",
        "deepseekv4.1-flash-1017,true,true",
        "deepseekv4-pro,true,false",
        "kimi-k3.1-pro,true,true",
        "qwen38-27b,true,true",
        "clef,false,false",
        "embeddinggemma-2,false,false"
    })
    void sarvamRecordsWhichModelsTakeASchemaAndWhichSeePictures(
            String model, boolean schema, boolean sees) {
        assertThat(Dialect.SARVAM.structuredOutput(model))
                .isEqualTo(
                        schema
                                ? io.github.llm4j.model.StructuredOutput.JSON_SCHEMA
                                : io.github.llm4j.model.StructuredOutput.NONE);
        assertThat(
                        Dialect.SARVAM
                                .mediaCapabilities(model)
                                .accepts(io.github.llm4j.model.MediaKind.IMAGE))
                .isEqualTo(sees);
    }

    @Test
    void qwenHybridModelsHaveAnEnableThinkingSwitchOthersDoNot() {
        assertThat(ModelTraits.qwen("qwen3-max").reasoningOn())
                .containsEntry("enable_thinking", true);
        assertThat(ModelTraits.qwen("qwen-plus").reasoningOff())
                .containsEntry("enable_thinking", false);
        assertThat(ModelTraits.qwen("qwen3.8-flash").reasoningOff())
                .containsEntry("enable_thinking", false);
        assertThat(ModelTraits.qwen("qwen3-235b-a22b-thinking-2507").reasoningOff()).isEmpty();
        assertThat(ModelTraits.qwen("qwq-plus").reasoningOff()).isEmpty();
        assertThat(ModelTraits.qwen("deepseek-r1").reasoningOff()).isEmpty();
    }

    /** A2: a made-up dialect works with no change to the provider. */
    @Test
    void aMadeUpDialectNeedsNoProviderChange() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}"));
            Dialect made =
                    Dialect.builder("acme")
                            .defaultBaseUrl("https://llm.acme.test/api")
                            .chatPath("/v9/complete")
                            .maxTokensField("max_new")
                            .authHeader("X-Acme-Key", "")
                            .keyEnv("ACME_KEY")
                            .baseUrlEnv("ACME_URL")
                            .hosts(List.of("llm.acme.test"))
                            .extraRequest(Map.of("acme_mode", "fast"))
                            .queryParams(Map.of("api-version", "2031-01"))
                            .build();
            var provider =
                    new OpenAiCompatibleProvider(
                            made,
                            LLMConfig.builder()
                                    .apiKey("k-1234567")
                                    .baseUrl(server.url("/api").toString())
                                    .defaultModel("a-1")
                                    .build());
            assertThat(provider.getProviderName()).isEqualTo("acme");
            assertThat(
                            provider.chat(
                                            LLMRequest.builder()
                                                    .addUserMessage("hi")
                                                    .maxTokens(9)
                                                    .build())
                                    .getContent())
                    .isEqualTo("hello");
            RecordedRequest r = server.takeRequest();
            assertThat(r.getPath()).isEqualTo("/api/v9/complete?api-version=2031-01");
            assertThat(r.getHeader("X-Acme-Key")).isEqualTo("k-1234567");
            JsonNode body = new ObjectMapper().readTree(r.getBody().readUtf8());
            assertThat(body.path("max_new").asInt()).isEqualTo(9);
            assertThat(body.path("acme_mode").asText()).isEqualTo("fast");
        }
    }

    @Test
    void theGenericDialectNeedsABaseUrlButNotAKey() throws Exception {
        assertThatThrownBy(
                        () ->
                                new OpenAiCompatibleProvider(
                                        Dialect.GENERIC, LLMConfig.builder().build()))
                .hasMessageContaining("base URL");
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}"));
            var p =
                    new OpenAiCompatibleProvider(
                            Dialect.GENERIC,
                            LLMConfig.builder()
                                    .baseUrl(server.url("/v1").toString())
                                    .defaultModel("local")
                                    .build());
            p.chat(LLMRequest.builder().addUserMessage("hi").build());
            assertThat(server.takeRequest().getHeader("Authorization")).isNull();
        }
    }
}
