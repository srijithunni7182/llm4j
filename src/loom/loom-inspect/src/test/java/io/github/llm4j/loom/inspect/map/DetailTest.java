package io.github.llm4j.loom.inspect.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DetailTest {

    private static final String LONG = "x".repeat(900);

    @Test
    void fullKeepsEverything() {
        assertThat(Detail.FULL.apply(LONG)).isEqualTo(LONG);
    }

    @Test
    void truncatedCutsToTheLimitAndSaysSo() {
        String cut = Detail.TRUNCATED.apply(LONG);
        assertThat(cut).hasSize(Detail.LIMIT + 1).endsWith("…");
        assertThat(Detail.TRUNCATED.apply("short")).isEqualTo("short");
    }

    @Test
    void metadataKeepsNoText() {
        assertThat(Detail.METADATA.apply("anything")).isNull();
    }

    @Test
    void emptyOrMissingTextIsNothing() {
        for (Detail d : Detail.values()) {
            assertThat(d.apply(null)).isNull();
            assertThat(d.apply("")).isNull();
        }
    }

    @Test
    void parsesTheThreeWordsAndDefaultsToTruncated() {
        assertThat(Detail.parse("FULL")).isEqualTo(Detail.FULL);
        assertThat(Detail.parse("metadata")).isEqualTo(Detail.METADATA);
        assertThat(Detail.parse(null)).isEqualTo(Detail.TRUNCATED);
        assertThatThrownBy(() -> Detail.parse("lots"))
                .hasMessageContaining("full, truncated or metadata");
    }
}
