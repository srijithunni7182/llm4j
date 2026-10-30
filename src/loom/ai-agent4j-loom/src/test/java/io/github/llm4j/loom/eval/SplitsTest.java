package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One reading of the split tags everywhere, and a sealed case is never taken for an open one. */
class SplitsTest {

    private static EvalScenario tagged(String... tags) {
        return new EvalScenario("n", "q", null, null, null, null, null, "id", null, List.of(tags));
    }

    @Test
    void aCaseWithNoSplitTagIsDevelopment() {
        assertThat(Splits.of(tagged("class:a"))).isEqualTo(Splits.DEVELOPMENT);
    }

    @Test
    void aHeldOutTagWinsOverAnyOtherSplitTagWhateverTheirOrder() {
        assertThat(Splits.of(tagged("split:development", "split:heldout"))).isEqualTo(Splits.HELD_OUT);
        assertThat(Splits.of(tagged("split:heldout", "split:validation"))).isEqualTo(Splits.HELD_OUT);
        assertThat(Splits.of(tagged("split:development", "split:validation"))).isEqualTo(Splits.VALIDATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"split:heldout", "split: heldout", " split:heldout ", "split:HeldOut", "split:heldout "})
    void everyWayOfWritingHeldOutIsHeldOutInEveryPlace(String tag) {
        assertThat(Splits.of(tagged(tag))).isEqualTo(Splits.HELD_OUT);
        assertThat(Splits.isHeldOut(List.of(tag))).isTrue();
        assertThat(Splits.has(List.of("other", tag), Splits.HELD_OUT)).isTrue();
        assertThat(Splits.has(List.of(tag), Splits.VALIDATION)).isFalse();
    }

    @Test
    void anUnknownSplitNameIsStillReportedAndTheCaseIsDevelopment() {
        assertThat(Splits.of(tagged("split:tuning"))).isEqualTo(Splits.DEVELOPMENT);
        assertThat(Splits.named(tagged("split:development", "split:tuning"))).isEqualTo("tuning");
    }

    @Test
    void aCaseWithoutTagsIsDevelopmentAndNotHeldOut() {
        assertThat(Splits.of(new EvalScenario("n", "q", null, null, null, null, null, "id", null, null)))
                .isEqualTo(Splits.DEVELOPMENT);
        assertThat(Splits.isHeldOut(List.of())).isFalse();
    }
}
