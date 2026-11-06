package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.TinyMedia;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * docs/specs/31-multimodal-input R14: a budget and a context window must count media, or a
 * media-heavy request slips past both. The estimates are deliberately high; the provider's reported
 * usage replaces them after the call.
 */
class MediaTokensTest {

    private static final MediaPolicy POLICY = MediaPolicy.defaults();

    private static Media of(MediaKind kind, byte[] header, int size) {
        return Media.of(kind, Arrays.copyOf(header, size), "x", POLICY);
    }

    // ── never below what a provider really reported ────────────────────────────────────────
    // Gemini prompt tokens on 2026-11-06 for gemini-2.5-flash, each including about 13 tokens of
    // question text: a 32x32 picture 268, a one-page PDF 268, 2 s of WAV 77, 2 s of MP3 78, a 2 s
    // MP4 clip 864.

    @Test
    void neverUnderstatesWhatGeminiReportedForRealFiles() {
        assertThat(MediaTokens.estimate(of(MediaKind.IMAGE, TinyMedia.png(), 97)))
                .isGreaterThanOrEqualTo(268);
        assertThat(MediaTokens.estimate(of(MediaKind.DOCUMENT, TinyMedia.pdf(), 585)))
                .isGreaterThanOrEqualTo(268);
        assertThat(MediaTokens.estimate(of(MediaKind.AUDIO, TinyMedia.wav(), 64_078)))
                .isGreaterThanOrEqualTo(77);
        assertThat(MediaTokens.estimate(of(MediaKind.AUDIO, TinyMedia.mp3(), 16_527)))
                .isGreaterThanOrEqualTo(78);
        assertThat(MediaTokens.estimate(of(MediaKind.VIDEO, TinyMedia.mp4(), 20_973)))
                .isGreaterThanOrEqualTo(864);
    }

    @Test
    void aPictureCostsTheSameWhateverItsSize() {
        assertThat(MediaTokens.estimate(of(MediaKind.IMAGE, TinyMedia.png(), 97))).isEqualTo(1_600);
        assertThat(MediaTokens.estimate(of(MediaKind.IMAGE, TinyMedia.png(), 5_000_000)))
                .isEqualTo(1_600);
    }

    @Test
    void aDocumentHasAFloorAndGrowsWithItsSize() {
        assertThat(MediaTokens.estimate(of(MediaKind.DOCUMENT, TinyMedia.pdf(), 585)))
                .isEqualTo(3_000);
        assertThat(MediaTokens.estimate(of(MediaKind.DOCUMENT, TinyMedia.pdf(), 1_000_000)))
                .isEqualTo(25_000);
    }

    @Test
    void aDocumentIsCappedSoOneHugeScanDoesNotMakeEveryBudgetRefuse() {
        MediaPolicy big = MediaPolicy.builder().maxBytesPerItem(16_000_000).build();
        Media huge =
                Media.of(MediaKind.DOCUMENT, Arrays.copyOf(TinyMedia.pdf(), 8_000_000), "x", big);

        assertThat(MediaTokens.estimate(huge)).isEqualTo(150_000);
    }

    @Test
    void soundAndVideoHaveFloorsAndGrowWithTheirSize() {
        assertThat(MediaTokens.estimate(of(MediaKind.AUDIO, TinyMedia.wav(), 64_078)))
                .isEqualTo(500);
        assertThat(MediaTokens.estimate(of(MediaKind.AUDIO, TinyMedia.wav(), 1_000_000)))
                .isEqualTo(4_000);
        assertThat(MediaTokens.estimate(of(MediaKind.VIDEO, TinyMedia.mp4(), 20_973)))
                .isEqualTo(2_000);
        assertThat(MediaTokens.estimate(of(MediaKind.VIDEO, TinyMedia.mp4(), 3_000_000)))
                .isEqualTo(20_000);
    }

    // ── the estimator adds them to the prompt ──────────────────────────────────────────────

    private static LLMRequest ask(Media... media) {
        return LLMRequest.builder()
                .addMessage(Message.user("What is in it?", List.of(media)))
                .build();
    }

    @Test
    void thePromptEstimateGrowsByExactlyTheMediaEstimate() {
        Media photo = of(MediaKind.IMAGE, TinyMedia.png(), 97);
        long without = CharsPerTokenEstimator.INSTANCE.prompt(ask());
        long with = CharsPerTokenEstimator.INSTANCE.prompt(ask(photo));

        assertThat(with - without).isEqualTo(MediaTokens.estimate(photo));
    }

    @Test
    void severalItemsAddUp() {
        Media photo = of(MediaKind.IMAGE, TinyMedia.png(), 97);
        Media pdf = of(MediaKind.DOCUMENT, TinyMedia.pdf(), 585);
        long without = CharsPerTokenEstimator.INSTANCE.prompt(ask());

        assertThat(CharsPerTokenEstimator.INSTANCE.prompt(ask(photo, pdf)) - without)
                .isEqualTo(MediaTokens.estimate(photo) + MediaTokens.estimate(pdf));
    }

    @Test
    void textOnlyEstimatesAreUnchanged() {
        // 14 characters of text: ceil(14 / 4 * 1.1) = 4
        assertThat(
                        CharsPerTokenEstimator.INSTANCE.prompt(
                                LLMRequest.builder().addUserMessage("What is in it?").build()))
                .isEqualTo(4);
    }

    @Test
    void aBudgetRefusesARequestWhoseMediaDoesNotFit() {
        Budget small = Budget.builder().tokens(1_000).build();
        io.github.llm4j.budget.BudgetedLLMClient client =
                io.github.llm4j.budget.BudgetedLLMClient.builder(
                                new io.github.llm4j.LLMClient() {
                                    @Override
                                    public io.github.llm4j.model.LLMResponse chat(LLMRequest r) {
                                        throw new AssertionError("the model must not be called");
                                    }

                                    @Override
                                    public java.util.stream.Stream<
                                                    io.github.llm4j.model.LLMResponse>
                                            chatStream(LLMRequest r) {
                                        return java.util.stream.Stream.empty();
                                    }
                                })
                        .budget(small)
                        .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.chat(ask(of(MediaKind.IMAGE, TinyMedia.png(), 97))))
                .isInstanceOf(BudgetExceeded.class);
    }
}
