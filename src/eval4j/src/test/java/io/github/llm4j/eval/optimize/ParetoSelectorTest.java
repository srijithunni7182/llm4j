package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ParetoSelectorTest {

    @Test
    void weightsCountScenariosWonAndDropDominatedAndBestOnNothing() {
        double[][] scores = {
            {1.0, 0.5, 0.5}, // A: best on scenario 0
            {0.5, 1.0, 1.0}, // B: best on 1 and 2
            {0.4, 0.4, 0.4}, // C: dominated by A and B
            {0.7, 0.7, 0.7}, // D: not dominated, best on nothing
        };

        assertThat(ParetoSelector.weights(scores)).containsExactly(1, 2, 0, 0);
    }

    @Test
    void tiesWithinEpsilonCountAsBestForBoth() {
        double[][] scores = {{0.8, 0.5}, {0.8 + 1e-12, 0.4}};
        // second candidate is best on scenario 0 (and ties the first there); first is best on 0 and
        // 1
        double[] weights = ParetoSelector.weights(scores);
        assertThat(weights[0]).isEqualTo(2);
        assertThat(weights[1]).isZero(); // dominated: never better, worse on scenario 1
    }

    @Test
    void identicalCandidatesShareWeight() {
        double[][] scores = {{0.9, 0.1}, {0.9, 0.1}};
        assertThat(ParetoSelector.weights(scores)).containsExactly(2, 2);
    }

    @Test
    void emptyPoolHasNoWeights() {
        assertThat(ParetoSelector.weights(new double[0][0])).isEmpty();
        assertThat(ParetoSelector.pick(new double[0], 0.5)).isEqualTo(-1);
        assertThat(ParetoSelector.pick(new double[] {0, 0}, 0.5)).isEqualTo(-1);
    }

    @Test
    void pickFollowsCumulativeWeights() {
        double[] weights = {1, 0, 3};
        assertThat(ParetoSelector.pick(weights, 0.0)).isEqualTo(0);
        assertThat(ParetoSelector.pick(weights, 0.24)).isEqualTo(0);
        assertThat(ParetoSelector.pick(weights, 0.25)).isEqualTo(2);
        assertThat(ParetoSelector.pick(weights, 0.999999)).isEqualTo(2);
        assertThat(ParetoSelector.pick(weights, 1.0))
                .isEqualTo(2); // guard against a draw at the boundary
    }

    @Test
    void empiricalSelectionFrequenciesMatchTheWeights() {
        double[] weights = {1, 2, 0, 3};
        SplittableRandom random = new SplittableRandom(12345);
        int[] counts = new int[4];
        int draws = 60_000;
        for (int i = 0; i < draws; i++) {
            counts[ParetoSelector.pick(weights, random.nextDouble())]++;
        }
        assertThat(counts[2]).isZero();
        assertThat(counts[0] / (double) draws).isBetween(1 / 6.0 - 0.01, 1 / 6.0 + 0.01);
        assertThat(counts[1] / (double) draws).isBetween(2 / 6.0 - 0.01, 2 / 6.0 + 0.01);
        assertThat(counts[3] / (double) draws).isBetween(3 / 6.0 - 0.01, 3 / 6.0 + 0.01);
    }

    @Test
    void propertyTheFrontierIsNeverEmptyAndABetterCandidateJoinsIt() {
        Random random = new Random(99);
        for (int iteration = 0; iteration < 300; iteration++) {
            int candidates = 1 + random.nextInt(6);
            int scenarios = 1 + random.nextInt(6);
            double[][] scores = new double[candidates][scenarios];
            for (double[] row : scores) {
                for (int v = 0; v < scenarios; v++) {
                    row[v] = random.nextInt(5) / 4.0;
                }
            }
            double[] weights = ParetoSelector.weights(scores);
            assertThat(java.util.Arrays.stream(weights).sum()).isPositive();

            // append a candidate that beats everyone everywhere: it must be on the frontier
            double[][] extended = java.util.Arrays.copyOf(scores, candidates + 1);
            extended[candidates] = new double[scenarios];
            java.util.Arrays.fill(extended[candidates], 2.0);
            assertThat(ParetoSelector.weights(extended)[candidates]).isEqualTo(scenarios);
        }
    }
}
