package io.github.llm4j.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.LLMProvider;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The registry of OpenAI-family vendors that configuration layers (Loom, Spring) share: kinds,
 * prefixes, keys, hosts, name inference.
 */
class VendorTest {

    private static LLMConfig config() {
        return LLMConfig.builder()
                .apiKey("k-1234567")
                .baseUrl("http://localhost:1/v1")
                .defaultModel("m")
                .build();
    }

    @Test
    void kindsAreTheNamesUsedInScripts() {
        assertThat(Vendor.kinds())
                .containsExactlyInAnyOrder(
                        "openai",
                        "xai",
                        "kimi",
                        "qwen",
                        "perplexity",
                        "perplexity_router",
                        "openai_compatible");
        assertThat(Vendor.ofKind("kimi")).contains(Vendor.KIMI);
        assertThat(Vendor.ofKind("gemini")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "openai/gpt-5-mini,OPENAI,gpt-5-mini",
        "xai/grok-4,XAI,grok-4",
        "kimi/kimi-k3,KIMI,kimi-k3",
        "qwen/qwen3-max,QWEN,qwen3-max",
        "perplexity/openai/gpt-5.6-sol,PERPLEXITY,openai/gpt-5.6-sol",
        "perplexity/preset:medium,PERPLEXITY,preset:medium",
        "gpt-5-mini,OPENAI,gpt-5-mini",
        "gpt-4.1,OPENAI,gpt-4.1",
        "o3-mini,OPENAI,o3-mini",
        "o4-mini,OPENAI,o4-mini",
        "o1,OPENAI,o1",
        "chatgpt-4o-latest,OPENAI,chatgpt-4o-latest",
        "grok-4,XAI,grok-4",
        "grok-3-mini,XAI,grok-3-mini",
        "kimi-k2.6,KIMI,kimi-k2.6",
        "moonshot-v1-8k,KIMI,moonshot-v1-8k"
    })
    void explicitPrefixesAndUnambiguousBareNamesResolve(String model, Vendor vendor, String sent) {
        assertThat(Vendor.ofModel(model))
                .hasValueSatisfying(
                        r -> {
                            assertThat(r.vendor()).isEqualTo(vendor);
                            assertThat(r.model()).isEqualTo(sent);
                        });
    }

    @ParameterizedTest
    @CsvSource({
        "qwen3",
        "qwen3:8b",
        "gpt-oss:20b",
        "gpt-oss",
        "llama3",
        "gemini-2.5-flash",
        "claude-opus-5-5",
        "ollama/qwen3",
        "o",
        "ollama",
        "o1x2",
        "some-model",
        "sarvam/sarvam-m"
    })
    void ambiguousOrOtherNamesStayUnresolved(String model) {
        assertThat(Vendor.ofModel(model)).isEmpty();
    }

    @Test
    void everyVendorNamesItsKeyBaseUrlAndHosts() {
        assertThat(Vendor.OPENAI.keyEnv()).isEqualTo("OPENAI_API_KEY");
        assertThat(Vendor.OPENAI.baseUrlEnv()).isEqualTo("OPENAI_BASE_URL");
        assertThat(Vendor.PERPLEXITY.keyEnv()).isEqualTo("PERPLEXITY_API_KEY");
        assertThat(Vendor.KIMI.keyEnv()).isEqualTo("MOONSHOT_API_KEY");
        assertThat(Vendor.QWEN.keyEnv()).isEqualTo("DASHSCOPE_API_KEY");
        assertThat(Vendor.XAI.hosts()).containsExactly("api.x.ai");
        assertThat(Vendor.PERPLEXITY.hosts()).containsExactly("api.perplexity.ai");
        assertThat(Vendor.OPENAI_COMPATIBLE.hosts()).isEmpty();
        assertThat(Vendor.KIMI.preview()).isTrue();
        assertThat(Vendor.QWEN.preview()).isTrue();
        assertThat(Vendor.PERPLEXITY_ROUTER.preview()).isTrue();
        assertThat(Vendor.OPENAI.preview()).isFalse();
    }

    @Test
    void createBuildsTheRightProvider() {
        assertThat(Vendor.OPENAI.create(config(), Map.of()).getProviderName()).isEqualTo("openai");
        assertThat(Vendor.XAI.create(config(), Map.of()).getProviderName()).isEqualTo("xai");
        assertThat(Vendor.KIMI.create(config(), Map.of()).getProviderName()).isEqualTo("kimi");
        assertThat(Vendor.QWEN.create(config(), Map.of()).getProviderName()).isEqualTo("qwen");
        LLMProvider perplexity = Vendor.PERPLEXITY.create(config(), Map.of());
        assertThat(perplexity).isInstanceOf(OpenAiResponsesProvider.class);
        assertThat(perplexity.getProviderName()).isEqualTo("perplexity");
        assertThat(Vendor.OPENAI.create(config(), Map.of("api", "responses")))
                .isInstanceOf(OpenAiResponsesProvider.class);
        assertThat(Vendor.OPENAI.create(config(), Map.of("api", "chat")))
                .isInstanceOf(OpenAiCompatibleProvider.class);
        assertThatThrownBy(() -> Vendor.OPENAI.create(config(), Map.of("api", "soap")))
                .hasMessageContaining("api")
                .hasMessageContaining("responses");
    }

    @Test
    void theGenericKindTakesAHeaderNameAndFixedQueryParametersOnly() {
        LLMProvider p =
                Vendor.OPENAI_COMPATIBLE.create(
                        config(),
                        Map.of("header", "api-key", "query", "api-version=2025-01-01&x=y"));
        Dialect d = ((OpenAiCompatibleProvider) p).dialect();
        assertThat(d.authHeader()).isEqualTo("api-key");
        assertThat(d.authScheme()).isEmpty();
        assertThat(d.queryParams())
                .containsEntry("api-version", "2025-01-01")
                .containsEntry("x", "y");
        assertThatThrownBy(() -> Vendor.OPENAI_COMPATIBLE.create(config(), Map.of("proxy", "x")))
                .hasMessageContaining("proxy");
        assertThatThrownBy(() -> Vendor.OPENAI.create(config(), Map.of("header", "api-key")))
                .hasMessageContaining("header");
    }
}
