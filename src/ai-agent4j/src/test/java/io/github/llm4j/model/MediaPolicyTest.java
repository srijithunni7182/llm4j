package io.github.llm4j.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** docs/specs/31-multimodal-input R8 and D8: the limits media must stay within. */
class MediaPolicyTest {

    @Test
    void theDefaultsAreTheOnesTheSpecNames() {
        MediaPolicy defaults = MediaPolicy.defaults();

        assertThat(defaults.maxBytesPerItem()).isEqualTo(20L * 1024 * 1024);
        assertThat(defaults.maxItemsPerMessage()).isEqualTo(4);
        assertThat(defaults.maxBytesPerRequest()).isEqualTo(32L * 1024 * 1024);
    }

    @Test
    void theDefaultsAllowEveryFormatTheLibraryKnowsAndNotSvg() {
        MediaPolicy defaults = MediaPolicy.defaults();

        assertThat(defaults.allows(MediaKind.IMAGE, "image/png")).isTrue();
        assertThat(defaults.allows(MediaKind.IMAGE, "image/webp")).isTrue();
        assertThat(defaults.allows(MediaKind.DOCUMENT, "application/pdf")).isTrue();
        assertThat(defaults.allows(MediaKind.AUDIO, "audio/mpeg")).isTrue();
        assertThat(defaults.allows(MediaKind.VIDEO, "video/mp4")).isTrue();
        assertThat(defaults.allows(MediaKind.IMAGE, "image/svg+xml")).isFalse();
        // a type of the wrong kind is not allowed for it
        assertThat(defaults.allows(MediaKind.IMAGE, "application/pdf")).isFalse();
    }

    @Test
    void allowReplacesAKindsListAndRefuseEmptiesIt() {
        MediaPolicy policy =
                MediaPolicy.builder()
                        .allow(MediaKind.IMAGE, "image/png")
                        .refuse(MediaKind.AUDIO)
                        .build();

        assertThat(policy.allows(MediaKind.IMAGE, "image/png")).isTrue();
        assertThat(policy.allows(MediaKind.IMAGE, "image/jpeg")).isFalse();
        assertThat(policy.allows(MediaKind.AUDIO, "audio/wav")).isFalse();
        assertThat(policy.allows(MediaKind.VIDEO, "video/mp4")).isTrue();
    }

    @Test
    void canNotBeMadeToAllowATypeTheLibraryCannotSniff() {
        assertThatThrownBy(() -> MediaPolicy.builder().allow(MediaKind.IMAGE, "image/svg+xml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image/svg+xml");
    }

    @Test
    void refusesLimitsThatAreNotPositive() {
        assertThatThrownBy(() -> MediaPolicy.builder().maxBytesPerItem(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBytesPerItem");
        assertThatThrownBy(() -> MediaPolicy.builder().maxItemsPerMessage(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItemsPerMessage");
        assertThatThrownBy(() -> MediaPolicy.builder().maxBytesPerRequest(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBytesPerRequest");
    }

    @Test
    void aPolicyIsAValue() {
        assertThat(MediaPolicy.defaults()).isEqualTo(MediaPolicy.builder().build());
        assertThat(MediaPolicy.builder().maxItemsPerMessage(2).build())
                .isNotEqualTo(MediaPolicy.defaults());
    }
}
