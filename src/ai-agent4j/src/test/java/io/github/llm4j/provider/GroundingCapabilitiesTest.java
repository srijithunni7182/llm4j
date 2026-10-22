package io.github.llm4j.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.openai.Vendor;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The table a configuration check reads says what the constructed providers say (8g): one fact in
 * two places must not drift.
 */
class GroundingCapabilitiesTest {

    private static final LLMConfig CONFIG =
            LLMConfig.builder().apiKey("k-1234567").defaultModel("m").build();

    @Test
    void theTableAgreesWithTheProviders() {
        assertThat(GroundingCapabilities.options("gemini", false))
                .hasValue(new GoogleProvider(CONFIG).groundingOptions());
        assertThat(GroundingCapabilities.options("anthropic", false))
                .hasValue(new AnthropicProvider(CONFIG).groundingOptions());
        assertThat(GroundingCapabilities.options("perplexity", false))
                .hasValue(Vendor.PERPLEXITY.create(CONFIG, Map.of()).groundingOptions());
        for (Vendor v : new Vendor[] {Vendor.OPENAI, Vendor.XAI}) {
            assertThat(GroundingCapabilities.options(v.kind(), true))
                    .as(v.kind() + " on the Responses API")
                    .hasValue(v.create(CONFIG, Map.of("api", "responses")).groundingOptions());
            assertThat(GroundingCapabilities.options(v.kind(), false))
                    .as(v.kind() + " on chat completions")
                    .isEmpty();
            assertThat(v.create(CONFIG, Map.of()).groundingOptions()).isEmpty();
        }
    }

    @Test
    void everyOtherKindCannotGround() {
        for (String kind :
                new String[] {
                    "ollama",
                    "sarvam",
                    "jev",
                    "kimi",
                    "qwen",
                    "perplexity_router",
                    "openai_compatible",
                    "nonesuch"
                }) {
            assertThat(GroundingCapabilities.options(kind, true)).as(kind).isEmpty();
        }
    }

    @Test
    void onlyOpenAiAndXaiHaveAResponsesApiToChoose() {
        assertThat(GroundingCapabilities.hasResponsesApi("openai")).isTrue();
        assertThat(GroundingCapabilities.hasResponsesApi("xai")).isTrue();
        assertThat(GroundingCapabilities.hasResponsesApi("perplexity")).isFalse();
        assertThat(GroundingCapabilities.hasResponsesApi("gemini")).isFalse();
    }
}
