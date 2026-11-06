package io.github.llm4j.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.exception.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** docs/specs/31-multimodal-input R10: what a model can take, and the refusal before any call. */
class MediaCapabilitiesTest {

    private static final MediaPolicy POLICY = MediaPolicy.defaults();

    private static LLMRequest requestWith(Media... media) {
        return LLMRequest.builder()
                .addMessage(Message.user("look", List.of(media)))
                .model("claude-test")
                .build();
    }

    private static Media image() {
        return Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", POLICY);
    }

    private static Media video() {
        return Media.of(MediaKind.VIDEO, TinyMedia.mp4(), "a.mp4", POLICY);
    }

    @Test
    void takesNothingUnlessToldOtherwise() {
        assertThat(MediaCapabilities.NONE.accepts(MediaKind.IMAGE)).isFalse();
        assertThat(MediaCapabilities.NONE.kinds()).isEmpty();
    }

    @Test
    void listsWhatItTakes() {
        MediaCapabilities caps = MediaCapabilities.of(MediaKind.IMAGE, MediaKind.DOCUMENT);

        assertThat(caps.accepts(MediaKind.IMAGE)).isTrue();
        assertThat(caps.accepts(MediaKind.VIDEO)).isFalse();
        assertThat(caps.kinds()).containsExactlyInAnyOrder(MediaKind.IMAGE, MediaKind.DOCUMENT);
    }

    @Test
    void aRequestWithoutMediaPassesWhateverTheModelTakes() {
        MediaCapabilities.NONE.require(
                "anthropic", LLMRequest.builder().addUserMessage("hi").build());
    }

    @Test
    void refusesMediaTheModelCannotTakeAndNamesWhatItCan() {
        MediaCapabilities caps = MediaCapabilities.of(MediaKind.IMAGE, MediaKind.DOCUMENT);

        assertThatThrownBy(() -> caps.require("anthropic", requestWith(image(), video())))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("anthropic")
                .hasMessageContaining("claude-test")
                .hasMessageContaining("does not accept video")
                .hasMessageContaining("image, document");
    }

    @Test
    void saysSoPlainlyWhenItTakesNoMediaAtAll() {
        assertThatThrownBy(() -> MediaCapabilities.NONE.require("sarvam", requestWith(image())))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not accept image")
                .hasMessageContaining("no media");
    }

    @Test
    void namesEveryKindThatIsRefusedOnce() {
        MediaCapabilities caps = MediaCapabilities.of(MediaKind.DOCUMENT);
        Media audio = Media.of(MediaKind.AUDIO, TinyMedia.wav(), "a.wav", POLICY);

        assertThatThrownBy(() -> caps.require("p", requestWith(image(), image(), video(), audio)))
                .hasMessageContaining("image, audio, video");
    }

    @Test
    void theRefusalNeverContainsTheMedia() {
        assertThatThrownBy(() -> MediaCapabilities.NONE.require("p", requestWith(image())))
                .satisfies(
                        e ->
                                assertThat(e.getMessage())
                                        .doesNotContain(TinyMedia.base64(TinyMedia.png()))
                                        .doesNotContain("a.png"));
    }

    @Test
    void whenNoModelIsNamedItSaysTheDefaultOne() {
        LLMRequest request =
                LLMRequest.builder().addMessage(Message.user("look", List.of(image()))).build();

        assertThatThrownBy(() -> MediaCapabilities.NONE.require("p", request))
                .hasMessageContaining("default model");
    }

    @Test
    void canCheckAgainstASetOfKindsAndALabelWhenThereIsNoRequestYet() {
        MediaCapabilities caps = MediaCapabilities.of(MediaKind.IMAGE);

        caps.require("agent Reader (gemini-2.5-flash)", java.util.Set.of(MediaKind.IMAGE));
        assertThatThrownBy(
                        () ->
                                caps.require(
                                        "agent Reader (gemini-2.5-flash)",
                                        java.util.Set.of(MediaKind.IMAGE, MediaKind.VIDEO)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("agent Reader (gemini-2.5-flash) does not accept video")
                .hasMessageContaining("accepted: image");
    }

    @Test
    void anEmptySetOfKindsAlwaysPasses() {
        MediaCapabilities.NONE.require("anything", java.util.Set.of());
    }

    @Test
    void whatTwoClientsBothTakeIsTheIntersection() {
        MediaCapabilities a = MediaCapabilities.of(MediaKind.IMAGE, MediaKind.VIDEO);
        MediaCapabilities b = MediaCapabilities.of(MediaKind.IMAGE, MediaKind.AUDIO);

        assertThat(a.and(b)).isEqualTo(MediaCapabilities.of(MediaKind.IMAGE));
        assertThat(a.and(MediaCapabilities.NONE)).isEqualTo(MediaCapabilities.NONE);
    }
}
