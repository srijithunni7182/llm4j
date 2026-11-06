package io.github.llm4j.provider.media;

import static io.github.llm4j.model.MediaKind.AUDIO;
import static io.github.llm4j.model.MediaKind.DOCUMENT;
import static io.github.llm4j.model.MediaKind.IMAGE;
import static io.github.llm4j.model.MediaKind.VIDEO;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.MediaCapabilities;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.ollama.OllamaProvider;
import io.github.llm4j.provider.openai.Dialect;
import io.github.llm4j.provider.openai.OpenAiCompatibleProvider;
import io.github.llm4j.provider.openai.OpenAiResponsesProvider;
import io.github.llm4j.provider.openai.ResponsesDialect;
import io.github.llm4j.provider.openai.Vendor;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * docs/specs/31-multimodal-input R10 and §4.3: what each provider and model takes. A cell marked
 * "live" was confirmed against the real API on 2026-11-06 (VERIFICATION §7); the others come from
 * the vendors' documentation. A model nobody recorded takes no media, so the answer to "unknown" is
 * always a refusal, never a guess.
 */
class MediaModelsTest {

    private static Set<MediaKind> kinds(String csv) {
        if (csv == null || csv.isBlank()) return EnumSet.noneOf(MediaKind.class);
        Set<MediaKind> out = EnumSet.noneOf(MediaKind.class);
        Arrays.stream(csv.split("\\+"))
                .map(String::strip)
                .map(MediaKind::valueOf)
                .forEach(out::add);
        return out;
    }

    @ParameterizedTest(name = "OpenAI chat {0} takes [{1}]")
    @CsvSource(
            delimiter = '|',
            value = {
                "gpt-5-mini         | IMAGE+DOCUMENT", // live
                "gpt-5              | IMAGE+DOCUMENT",
                "gpt-4o             | IMAGE+DOCUMENT",
                "gpt-4.1-mini       | IMAGE+DOCUMENT",
                "o3                 | IMAGE+DOCUMENT",
                "gpt-audio-mini     | AUDIO", // live
                "gpt-audio          | AUDIO", // live
                "gpt-4o-audio-preview | AUDIO",
                "gpt-4o-mini-audio-preview | AUDIO",
                "o1-mini            | ",
                "o3-mini            | ",
                "gpt-3.5-turbo      | ",
                "gpt-4-turbo        | ",
                "gpt-oss-120b       | ",
                "text-embedding-3-small | ",
                "a-model-nobody-recorded | "
            })
    void openAiChatByModel(String model, String expected) {
        assertThat(Dialect.OPENAI.mediaCapabilities(model.strip()).kinds())
                .containsExactlyInAnyOrderElementsOf(kinds(expected));
    }

    @ParameterizedTest(name = "OpenAI Responses {0} takes [{1}]")
    @CsvSource(
            delimiter = '|',
            value = {
                "gpt-5-mini     | IMAGE+DOCUMENT", // live
                "gpt-4o         | IMAGE+DOCUMENT",
                "gpt-audio      | ", // audio is chat-completions only; not offered here
                "o1-mini        | ",
                "unknown        | "
            })
    void openAiResponsesByModel(String model, String expected) {
        assertThat(ResponsesDialect.OPENAI.mediaCapabilities(model.strip()).kinds())
                .containsExactlyInAnyOrderElementsOf(kinds(expected));
    }

    @ParameterizedTest(name = "{0} {1} takes [{2}]")
    @CsvSource(
            delimiter = '|',
            value = {
                "xai    | grok-4.20-0309-non-reasoning | IMAGE", // live
                "xai    | grok-4                       | IMAGE",
                "xai    | grok-2-vision-1212           | IMAGE",
                "xai    | grok-3                       | ",
                "kimi   | moonshot-v1-8k-vision-preview | IMAGE",
                "kimi   | kimi-k2                       | ",
                "qwen   | qwen-vl-max                   | IMAGE",
                "qwen   | qwen3-vl-plus                 | IMAGE",
                "qwen   | qwen-plus                     | ",
                "generic| llava                         | ",
                "router | sonar                         | "
            })
    void otherChatVendors(String vendor, String model, String expected) {
        Dialect dialect =
                switch (vendor) {
                    case "xai" -> Dialect.XAI;
                    case "kimi" -> Dialect.KIMI;
                    case "qwen" -> Dialect.QWEN;
                    case "router" -> Dialect.PERPLEXITY_ROUTER;
                    default -> Dialect.GENERIC;
                };
        assertThat(dialect.mediaCapabilities(model.strip()).kinds())
                .containsExactlyInAnyOrderElementsOf(kinds(expected));
    }

    @Test
    void xaiResponsesAndPerplexityResponsesFollowTheirOwnRecords() {
        assertThat(ResponsesDialect.XAI.mediaCapabilities("grok-4").kinds()).containsExactly(IMAGE);
        assertThat(ResponsesDialect.PERPLEXITY.mediaCapabilities("sonar").kinds()).isEmpty();
    }

    @Test
    void aDialectWithNothingRecordedTakesNothing() {
        assertThat(Dialect.builder("custom").build().mediaCapabilities("anything"))
                .isEqualTo(MediaCapabilities.NONE);
        assertThat(Dialect.OPENAI.mediaCapabilities(null)).isEqualTo(MediaCapabilities.NONE);
    }

    @Test
    void theProviderAnswersForItsConfiguredModelWhenNoneIsNamed() {
        LLMConfig config =
                LLMConfig.builder().apiKey("test-key").defaultModel("gpt-5-mini").build();

        assertThat(
                        new OpenAiCompatibleProvider(Dialect.OPENAI, config)
                                .mediaCapabilities(null)
                                .kinds())
                .containsExactlyInAnyOrder(IMAGE, DOCUMENT);
        assertThat(
                        new OpenAiResponsesProvider(ResponsesDialect.OPENAI, config)
                                .mediaCapabilities(null)
                                .kinds())
                .containsExactlyInAnyOrder(IMAGE, DOCUMENT);
    }

    @ParameterizedTest(name = "Anthropic {0} takes [IMAGE, DOCUMENT]")
    @CsvSource({
        "claude-sonnet-5-5",
        "claude-haiku-5-5",
        "claude-opus-5-5",
        "claude-opus-4-1",
        "claude-3-5-haiku-20241022"
    })
    void anthropicTakesPicturesAndPdfsOnEveryClaudeModel(String model) {
        AnthropicProvider provider = new AnthropicProvider(LLMConfig.builder().apiKey("k").build());

        assertThat(provider.mediaCapabilities(model).kinds())
                .containsExactlyInAnyOrder(IMAGE, DOCUMENT);
    }

    @Test
    void anthropicTakesNothingOnAModelThatIsNotAClaude() {
        AnthropicProvider provider = new AnthropicProvider(LLMConfig.builder().apiKey("k").build());

        assertThat(provider.mediaCapabilities("gpt-5-mini")).isEqualTo(MediaCapabilities.NONE);
        assertThat(provider.mediaCapabilities(null)).isEqualTo(MediaCapabilities.NONE);
    }

    @Test
    void anthropicTakesNeitherSoundNorVideo() {
        // live: Messages API answers 400 "Input tag 'audio' ... does not match any of the expected
        // tags"
        AnthropicProvider provider =
                new AnthropicProvider(
                        LLMConfig.builder().apiKey("k").defaultModel("claude-sonnet-5-5").build());

        assertThat(provider.mediaCapabilities(null).accepts(AUDIO)).isFalse();
        assertThat(provider.mediaCapabilities(null).accepts(VIDEO)).isFalse();
    }

    @ParameterizedTest(name = "Google {0} takes [{1}]")
    @CsvSource(
            delimiter = '|',
            value = {
                "gemini-2.5-flash | IMAGE+AUDIO+VIDEO+DOCUMENT", // live, all four
                "gemini-2.5-pro   | IMAGE+AUDIO+VIDEO+DOCUMENT",
                "gemini-3-flash-preview | IMAGE+AUDIO+VIDEO+DOCUMENT",
                "gemini-flash-latest | IMAGE+AUDIO+VIDEO+DOCUMENT",
                "models/gemini-2.5-flash | IMAGE+AUDIO+VIDEO+DOCUMENT",
                "gemini-2.5-flash-preview-tts | ",
                "text-bison | "
            })
    void googleByModel(String model, String expected) {
        GoogleProvider provider = new GoogleProvider(LLMConfig.builder().apiKey("k").build());

        assertThat(provider.mediaCapabilities(model.strip()).kinds())
                .containsExactlyInAnyOrderElementsOf(kinds(expected));
    }

    @ParameterizedTest(name = "Ollama {0} takes [{1}]")
    @CsvSource(
            delimiter = '|',
            value = {
                "gemma3:4b          | IMAGE",
                "llava:7b           | IMAGE",
                "llama3.2-vision    | IMAGE",
                "qwen2.5vl:7b       | IMAGE",
                "minicpm-v          | IMAGE",
                "llama3.1:8b        | ",
                "mistral            | "
            })
    void ollamaTakesPicturesOnlyOnVisionModels(String model, String expected) {
        OllamaProvider provider =
                new OllamaProvider(
                        LLMConfig.builder().baseUrl("http://localhost:11434/api").build());

        assertThat(provider.mediaCapabilities(model.strip()).kinds())
                .containsExactlyInAnyOrderElementsOf(kinds(expected));
    }

    @Test
    void whatAModelTakesCanBeAskedByNameAloneWithoutAProviderOrAKey() {
        // weave check answers from a model name before any credential exists
        assertThat(AnthropicProvider.mediaCapabilitiesOf("claude-sonnet-5-5").kinds())
                .containsExactlyInAnyOrder(IMAGE, DOCUMENT);
        assertThat(AnthropicProvider.mediaCapabilitiesOf("gpt-5"))
                .isEqualTo(MediaCapabilities.NONE);
        assertThat(AnthropicProvider.mediaCapabilitiesOf(null)).isEqualTo(MediaCapabilities.NONE);
        assertThat(GoogleProvider.mediaCapabilitiesOf("gemini-2.5-flash").kinds())
                .containsExactlyInAnyOrder(IMAGE, AUDIO, VIDEO, DOCUMENT);
        assertThat(GoogleProvider.mediaCapabilitiesOf("gemini-2.5-flash-preview-tts"))
                .isEqualTo(MediaCapabilities.NONE);
        assertThat(OllamaProvider.mediaCapabilitiesOf("gemma3:4b").kinds()).containsExactly(IMAGE);
        assertThat(OllamaProvider.mediaCapabilitiesOf("llama3.1:8b"))
                .isEqualTo(MediaCapabilities.NONE);
    }

    @Test
    void aVendorAnswersByModelNameToo() {
        assertThat(Vendor.OPENAI.mediaCapabilities("gpt-5-mini").kinds())
                .containsExactlyInAnyOrder(IMAGE, DOCUMENT);
        assertThat(Vendor.XAI.mediaCapabilities("grok-4").kinds()).containsExactly(IMAGE);
        // Perplexity has only the Responses record, which records nothing
        assertThat(Vendor.PERPLEXITY.mediaCapabilities("sonar")).isEqualTo(MediaCapabilities.NONE);
    }

    @Test
    void providersThatRecordedNothingTakeNothing() {
        assertThat(
                        new SarvamChatProvider(LLMConfig.builder().apiKey("k").build())
                                .mediaCapabilities("sarvam-m"))
                .isEqualTo(MediaCapabilities.NONE);
    }
}
