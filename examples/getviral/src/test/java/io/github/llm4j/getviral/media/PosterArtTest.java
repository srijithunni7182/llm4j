package io.github.llm4j.getviral.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PosterArtTest {

    @Test
    void textKeepsWhatTheFontCanDrawAndDropsColourEmoji() {
        var font = PosterArt.display(40);
        assertThat(PosterArt.drawable(font, "SAVE THIS 🔖")).isEqualTo("SAVE THIS");
        assertThat(PosterArt.drawable(font, "3 tiny steps 👩🏽‍🍳 that stick")).isEqualTo("3 tiny steps that stick");
        assertThat(PosterArt.drawable(font, "Café — 2 minutes?!")).isEqualTo("Café — 2 minutes?!");
    }
}
