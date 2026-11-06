package io.github.llm4j.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.Message;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.openai.OpenAiCompatibleProvider;
import io.github.llm4j.provider.openai.OpenAiResponsesProvider;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pictures, PDFs, sound and video against the real APIs (docs/specs/31-multimodal-input, 31c), for
 * whichever keys are set. The wire shapes were first confirmed by hand on 2026-11-06; these tests
 * keep them confirmed. They are tagged {@code live}: they cost a few cents, never run in a default
 * build, and run with {@code mvn -pl src/ai-agent4j -Plive test}. A video needs {@code
 * LLM4J_LIVE_MP4} to name a short clip of a solid red screen.
 *
 * <p>Keys: {@code ANTHROPIC_API_KEY}, {@code GEMINI_API_KEY}, {@code OPENAI_API_KEY}, {@code
 * XAI_API_KEY}. Each client is metered against {@link LiveTargets#budget(String)}.
 */
@Tag("live")
class LiveMediaTest {

    private static final String COLOUR_QUESTION =
            "Which colour fills this picture? Answer with exactly one word: red, green or blue.";
    private static final String TOTAL_QUESTION = "What number follows TOTAL? Digits only.";
    private static final String SOUND_QUESTION =
            "Is this a spoken sentence or a steady tone? Answer with one word: speech or tone.";

    private static LLMClient metered(String name, io.github.llm4j.provider.LLMProvider provider) {
        return BudgetedLLMClient.builder(new DefaultLLMClient(provider))
                .budget(LiveTargets.budget(name))
                .build();
    }

    private static LLMClient anthropic() {
        String key = LiveTargets.env("ANTHROPIC_API_KEY");
        assumeTrue(key != null, "ANTHROPIC_API_KEY not set");
        String model = LiveTargets.models("ANTHROPIC_TEST_MODELS", "claude-haiku-5-5").get(0);
        return metered(
                "anthropic",
                new AnthropicProvider(LLMConfig.builder().apiKey(key).defaultModel(model).build()));
    }

    private static LLMClient google() {
        String key = LiveTargets.env("GEMINI_API_KEY");
        assumeTrue(key != null, "GEMINI_API_KEY not set");
        String model = LiveTargets.models("GEMINI_TEST_MODELS", "gemini-2.5-flash").get(0);
        return metered(
                "gemini",
                new GoogleProvider(LLMConfig.builder().apiKey(key).defaultModel(model).build()));
    }

    private static LLMClient openAiChat(String model) {
        String key = LiveTargets.env("OPENAI_API_KEY");
        assumeTrue(key != null, "OPENAI_API_KEY not set");
        return metered(
                "openai",
                OpenAiCompatibleProvider.openai(
                        LLMConfig.builder().apiKey(key).defaultModel(model).build()));
    }

    private static LLMClient openAiResponses() {
        String key = LiveTargets.env("OPENAI_API_KEY");
        assumeTrue(key != null, "OPENAI_API_KEY not set");
        return metered(
                "openai",
                OpenAiResponsesProvider.openai(
                        LLMConfig.builder().apiKey(key).defaultModel("gpt-5-mini").build()));
    }

    private static LLMClient xai() {
        String key = LiveTargets.env("XAI_API_KEY");
        assumeTrue(key != null, "XAI_API_KEY not set");
        return metered(
                "xai",
                OpenAiCompatibleProvider.xai(
                        LLMConfig.builder()
                                .apiKey(key)
                                .defaultModel("grok-4.20-0309-non-reasoning")
                                .build()));
    }

    private static String ask(LLMClient client, String question, Media media) {
        LLMResponse response =
                client.chat(
                        LLMRequest.builder()
                                .addMessage(Message.user(question, List.of(media)))
                                .maxTokens(1500)
                                .build());
        return response.getContent();
    }

    // ── pictures ───────────────────────────────────────────────────────────────────────────

    @Test
    void anthropicSeesAPicture() {
        assertThat(ask(anthropic(), COLOUR_QUESTION, LiveMedia.redPicture()))
                .containsIgnoringCase("red");
    }

    @Test
    void googleSeesAPicture() {
        assertThat(ask(google(), COLOUR_QUESTION, LiveMedia.redPicture()))
                .containsIgnoringCase("red");
    }

    @Test
    void openAiChatSeesAPicture() {
        assertThat(ask(openAiChat("gpt-5-mini"), COLOUR_QUESTION, LiveMedia.redPicture()))
                .containsIgnoringCase("red");
    }

    @Test
    void openAiResponsesSeesAPicture() {
        assertThat(ask(openAiResponses(), COLOUR_QUESTION, LiveMedia.redPicture()))
                .containsIgnoringCase("red");
    }

    @Test
    void xaiSeesAPicture() {
        assertThat(ask(xai(), COLOUR_QUESTION, LiveMedia.redPicture())).containsIgnoringCase("red");
    }

    // ── PDFs ───────────────────────────────────────────────────────────────────────────────

    @Test
    void anthropicReadsAPdf() {
        assertThat(ask(anthropic(), TOTAL_QUESTION, LiveMedia.totalPdf()))
                .contains(LiveMedia.TOTAL);
    }

    @Test
    void googleReadsAPdf() {
        assertThat(ask(google(), TOTAL_QUESTION, LiveMedia.totalPdf())).contains(LiveMedia.TOTAL);
    }

    @Test
    void openAiChatReadsAPdf() {
        assertThat(ask(openAiChat("gpt-5-mini"), TOTAL_QUESTION, LiveMedia.totalPdf()))
                .contains(LiveMedia.TOTAL);
    }

    @Test
    void openAiResponsesReadsAPdf() {
        assertThat(ask(openAiResponses(), TOTAL_QUESTION, LiveMedia.totalPdf()))
                .contains(LiveMedia.TOTAL);
    }

    // ── sound and video ────────────────────────────────────────────────────────────────────

    @Test
    void googleHearsATone() {
        assertThat(ask(google(), SOUND_QUESTION, LiveMedia.tone())).containsIgnoringCase("tone");
    }

    @Test
    void openAiAudioModelsAcceptSound() {
        // the wire shape is what is checked: a small audio model's judgement of a tone varies
        assertThat(ask(openAiChat("gpt-audio-mini"), SOUND_QUESTION, LiveMedia.tone()))
                .isNotBlank();
    }

    @Test
    void googleWatchesAVideo() throws Exception {
        assumeTrue(LiveMedia.hasFile("LLM4J_LIVE_MP4"), "LLM4J_LIVE_MP4 not set");
        assertThat(ask(google(), COLOUR_QUESTION.replace("picture", "video"), LiveMedia.clip()))
                .containsIgnoringCase("red");
    }
}
