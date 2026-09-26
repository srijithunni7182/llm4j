package io.github.llm4j.eval.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PassRateTest {

    @Test
    void record_countsPassingAssertionsWithoutThrowing() {
        PassRate passRate = new PassRate();

        passRate.record(() -> {});

        assertThat(passRate.passedCount()).isEqualTo(1);
        assertThat(passRate.totalCount()).isEqualTo(1);
        assertThat(passRate.rate()).isEqualTo(1.0);
    }

    @Test
    void record_countsFailingAssertionsWithoutPropagating() {
        PassRate passRate = new PassRate();

        passRate.record(
                () -> {
                    throw new AssertionError("boom");
                });

        assertThat(passRate.passedCount()).isEqualTo(0);
        assertThat(passRate.totalCount()).isEqualTo(1);
        assertThat(passRate.rate()).isEqualTo(0.0);
    }

    @Test
    void rate_reflectsMixOfPassAndFail() {
        PassRate passRate = new PassRate();
        passRate.record(() -> {});
        passRate.record(() -> {});
        passRate.record(
                () -> {
                    throw new AssertionError("nope");
                });

        assertThat(passRate.rate()).isEqualTo(2.0 / 3.0);
    }

    @Test
    void requireAtLeast_passesWhenRateMeetsThreshold() {
        PassRate passRate = new PassRate();
        passRate.record(() -> {});
        passRate.record(() -> {});

        passRate.requireAtLeast(0.9);
    }

    @Test
    void requireAtLeast_throwsAndListsFailuresWhenRateBelowThreshold() {
        PassRate passRate = new PassRate();
        passRate.record(() -> {});
        passRate.record(
                () -> {
                    throw new AssertionError("case 2 failed");
                });

        assertThatThrownBy(() -> passRate.requireAtLeast(0.9))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("0.50")
                .hasMessageContaining("case 2 failed");
    }

    @Test
    void rate_isZeroWhenNothingRecorded() {
        assertThat(new PassRate().rate()).isEqualTo(0.0);
    }
}
