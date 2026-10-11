package io.github.llm4j.loom.autonomy.expr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.autonomy.AgreementStats;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The 95% Wilson interval used by {@code rate(...).lower95/.upper95}. */
@Tag("L1")
class WilsonTest {

    @Test
    void lowerMatchesPublishedReferenceValuesAndTheExistingImplementation() {
        assertThat(Wilson.lower(0, 0)).isZero();
        assertThat(Wilson.lower(20, 20)).isCloseTo(0.838875, Offset.offset(1e-6));
        assertThat(Wilson.lower(90, 100)).isCloseTo(0.825634, Offset.offset(1e-6));
        assertThat(Wilson.lower(5, 10)).isCloseTo(0.236593, Offset.offset(1e-6));
        for (int n = 0; n <= 60; n++) {
            for (int k = 0; k <= n; k++) assertThat(Wilson.lower(k, n)).isCloseTo(AgreementStats.wilsonLower(k, n), Offset.offset(1e-9));
        }
    }

    @Test
    void upperIsTheMirrorOfLower() {
        assertThat(Wilson.upper(0, 0)).isZero();
        assertThat(Wilson.upper(20, 20)).isCloseTo(1.0, Offset.offset(1e-9));
        for (int n = 1; n <= 40; n++) {
            for (int k = 0; k <= n; k++) {
                assertThat(Wilson.upper(k, n)).isCloseTo(1 - Wilson.lower(n - k, n), Offset.offset(1e-9));
                assertThat(Wilson.lower(k, n)).isLessThanOrEqualTo((double) k / n + 1e-12);
                assertThat(Wilson.upper(k, n)).isGreaterThanOrEqualTo((double) k / n - 1e-12);
            }
        }
    }

    @Test
    void twentyOutOfTwentyIsNotOneHundredPercentEvidence() {
        assertThat(Wilson.lower(20, 20)).isLessThan(0.9);
    }
}
