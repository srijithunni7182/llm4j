package io.github.llm4j.eval.optimize;

/**
 * Pareto-frontier logic for choosing which candidate to improve next (the "Pareto" in GEPA). Pure
 * functions over a score matrix {@code scores[candidate][scenario]}, so they are exactly testable.
 *
 * <p>A candidate is <em>best on</em> a scenario if its score is within {@code epsilon} of the
 * maximum for that scenario. Candidates dominated by another (never worse anywhere, better
 * somewhere) get weight 0, as do candidates best on nothing. Everyone else is weighted by the
 * number of scenarios they are best on, so broadly strong specialists are picked more often while
 * different specialists all stay in play.
 */
final class ParetoSelector {

    static final double EPSILON = 1e-9;

    private ParetoSelector() {}

    static double[] weights(double[][] scores) {
        int candidates = scores.length;
        double[] weights = new double[candidates];
        if (candidates == 0) {
            return weights;
        }
        int scenarios = scores[0].length;
        double[] max = new double[scenarios];
        for (int v = 0; v < scenarios; v++) {
            max[v] = Double.NEGATIVE_INFINITY;
            for (double[] row : scores) {
                max[v] = Math.max(max[v], row[v]);
            }
        }
        for (int i = 0; i < candidates; i++) {
            if (dominated(scores, i)) {
                continue;
            }
            int bestOn = 0;
            for (int v = 0; v < scenarios; v++) {
                if (scores[i][v] >= max[v] - EPSILON) {
                    bestOn++;
                }
            }
            weights[i] = bestOn;
        }
        return weights;
    }

    /** Whether some other candidate is at least as good on every scenario and better on one. */
    private static boolean dominated(double[][] scores, int index) {
        for (int j = 0; j < scores.length; j++) {
            if (j == index) {
                continue;
            }
            boolean atLeastAsGood = true;
            boolean better = false;
            for (int v = 0; v < scores[index].length; v++) {
                if (scores[j][v] < scores[index][v] - EPSILON) {
                    atLeastAsGood = false;
                    break;
                }
                if (scores[j][v] > scores[index][v] + EPSILON) {
                    better = true;
                }
            }
            if (atLeastAsGood && better) {
                return true;
            }
        }
        return false;
    }

    /**
     * Picks an index with probability proportional to its weight, given a uniform draw in {@code
     * [0, 1)}. Returns -1 when every weight is zero.
     */
    static int pick(double[] weights, double uniform) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        if (total <= 0) {
            return -1;
        }
        double target = uniform * total;
        double cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (weights[i] > 0 && target < cumulative) {
                return i;
            }
        }
        for (int i = weights.length - 1; i >= 0; i--) {
            if (weights[i] > 0) {
                return i;
            }
        }
        return -1;
    }
}
