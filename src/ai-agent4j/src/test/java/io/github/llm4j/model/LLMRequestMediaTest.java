package io.github.llm4j.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** docs/specs/31-multimodal-input R8: a request bounds its media per message and per request. */
class LLMRequestMediaTest {

    private static final MediaPolicy ROOMY = MediaPolicy.defaults();

    private static Media photo() {
        return Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", ROOMY);
    }

    private static Message withPhotos(int count) {
        return Message.user("look", java.util.Collections.nCopies(count, photo()));
    }

    @Test
    void acceptsAsManyItemsAsTheDefaultPolicyAllows() {
        LLMRequest request = LLMRequest.builder().addMessage(withPhotos(4)).build();

        assertThat(request.getMessages().get(0).getMedia()).hasSize(4);
    }

    @Test
    void refusesMoreItemsInOneMessageThanThePolicyAllows() {
        LLMRequest.Builder request = LLMRequest.builder().addMessage(withPhotos(5));

        assertThatThrownBy(request::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5")
                .hasMessageContaining("4")
                .hasMessageContaining("per message");
    }

    @Test
    void refusesMoreBytesInTotalThanThePolicyAllows() {
        MediaPolicy tight = MediaPolicy.builder().maxBytesPerRequest(100).build();
        // two messages of 64 bytes each: fine one at a time, 128 together
        LLMRequest.Builder request =
                LLMRequest.builder()
                        .mediaPolicy(tight)
                        .addMessage(withPhotos(1))
                        .addAssistantMessage("ok")
                        .addMessage(withPhotos(1));

        assertThatThrownBy(request::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128")
                .hasMessageContaining("100")
                .hasMessageContaining("per request");
    }

    @Test
    void refusesAnItemBiggerThanThePoliciesItemLimitEvenIfItWasMadeUnderAnother() {
        MediaPolicy tight = MediaPolicy.builder().maxBytesPerItem(32).build();
        LLMRequest.Builder request =
                LLMRequest.builder().mediaPolicy(tight).addMessage(withPhotos(1));

        assertThatThrownBy(request::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64")
                .hasMessageContaining("32");
    }

    @Test
    void aLooserPolicyLetsMoreThrough() {
        MediaPolicy loose = MediaPolicy.builder().maxItemsPerMessage(6).build();

        assertThat(LLMRequest.builder().mediaPolicy(loose).addMessage(withPhotos(6)).build())
                .isNotNull();
    }

    @Test
    void theRequestKeepsItsPolicyThroughACopy() {
        MediaPolicy loose = MediaPolicy.builder().maxItemsPerMessage(6).build();
        LLMRequest request =
                LLMRequest.builder().mediaPolicy(loose).addMessage(withPhotos(6)).build();

        assertThat(request.getMediaPolicy()).isEqualTo(loose);
        assertThat(request.toBuilder().build().getMediaPolicy()).isEqualTo(loose);
        assertThat(LLMRequest.builder().addUserMessage("hi").build().getMediaPolicy())
                .isEqualTo(MediaPolicy.defaults());
    }

    @Test
    void reportsWhichKindsAreInIt() {
        Media clip = Media.of(MediaKind.VIDEO, TinyMedia.mp4(), "c.mp4", ROOMY);
        LLMRequest request =
                LLMRequest.builder()
                        .addUserMessage("text only")
                        .addMessage(Message.user("look", List.of(photo(), clip)))
                        .build();

        assertThat(request.mediaKinds())
                .containsExactlyInAnyOrder(MediaKind.IMAGE, MediaKind.VIDEO);
        assertThat(LLMRequest.builder().addUserMessage("hi").build().mediaKinds()).isEmpty();
    }

    @Test
    void aRequestThatDiffersOnlyInItsMediaIsNotEqual() {
        LLMRequest plain = LLMRequest.builder().addUserMessage("look").build();
        LLMRequest withMedia =
                LLMRequest.builder().addMessage(Message.user("look", List.of(photo()))).build();

        assertThat(withMedia).isNotEqualTo(plain);
    }
}
