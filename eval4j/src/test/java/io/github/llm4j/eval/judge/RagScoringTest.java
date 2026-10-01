package io.github.llm4j.eval.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RagScoringTest {

    private static boolean[] flags(int... bits) {
        boolean[] out = new boolean[bits.length];
        for (int i = 0; i < bits.length; i++) {
            out[i] = bits[i] == 1;
        }
        return out;
    }

    @Test
    void precision_allRelevantIsOne() {
        assertThat(RagScoring.precision(flags(1, 1, 1))).isEqualTo(1.0);
    }

    @Test
    void precision_noneRelevantIsZero() {
        assertThat(RagScoring.precision(flags(0, 0, 0))).isEqualTo(0.0);
    }

    @Test
    void precision_knownRankings() {
        assertThat(RagScoring.precision(flags(1, 0, 1))).isCloseTo(0.8333, within(1e-4));
        assertThat(RagScoring.precision(flags(0, 1, 1))).isCloseTo(0.5833, within(1e-4));
        assertThat(RagScoring.precision(flags(1, 1, 0))).isEqualTo(1.0);
        assertThat(RagScoring.precision(flags(1))).isEqualTo(1.0);
        assertThat(RagScoring.precision(flags(0))).isEqualTo(0.0);
    }

    @Test
    void precision_relevantLastScoresLowerThanRelevantFirstWithSameRelevancy() {
        assertThat(RagScoring.relevancy(flags(0, 0, 1)))
                .isEqualTo(RagScoring.relevancy(flags(1, 0, 0)));
        assertThat(RagScoring.precision(flags(0, 0, 1)))
                .isLessThan(RagScoring.precision(flags(1, 0, 0)));
    }

    @Test
    void precision_propertyBoundedAndMonotonicUnderPromotion() {
        Random random = new Random(42);
        for (int iteration = 0; iteration < 500; iteration++) {
            int n = 1 + random.nextInt(8);
            boolean[] flags = new boolean[n];
            for (int i = 0; i < n; i++) {
                flags[i] = random.nextBoolean();
            }
            double p = RagScoring.precision(flags);
            assertThat(p).isBetween(0.0, 1.0);
            for (int i = 0; i + 1 < n; i++) {
                if (!flags[i] && flags[i + 1]) {
                    boolean[] promoted = flags.clone();
                    promoted[i] = true;
                    promoted[i + 1] = false;
                    assertThat(RagScoring.precision(promoted)).isGreaterThanOrEqualTo(p);
                }
            }
        }
    }

    @Test
    void relevancy_isFractionAndEmptyIsZero() {
        assertThat(RagScoring.relevancy(flags(1, 0, 1, 0))).isEqualTo(0.5);
        assertThat(RagScoring.relevancy(new boolean[0])).isEqualTo(0.0);
    }

    @Test
    void recall_isFractionAndNothingToVerifyIsOne() {
        assertThat(RagScoring.recall(2, 4)).isEqualTo(0.5);
        assertThat(RagScoring.recall(0, 0)).isEqualTo(1.0);
    }
}
