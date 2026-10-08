package io.github.llm4j.eval.optimize;

import io.github.llm4j.eval.compare.PromptComparison;
import io.github.llm4j.eval.criteria.Scorecard;
import java.util.List;

/**
 * Paired comparison of the best candidate against the seed on the test split, by per-scenario
 * score. {@code interval} is the 95% Wilson interval on the share of decisive scenarios won by the
 * best candidate (null when nothing was decisive).
 */
public record Comparison(int bestWins, int seedWins, int ties, PromptComparison.Interval interval) {

    private static final double EPSILON = 1e-9;

    static Comparison of(List<Scorecard> seed, List<Scorecard> best) {
        int bestWins = 0;
        int seedWins = 0;
        int ties = 0;
        for (int i = 0; i < Math.min(seed.size(), best.size()); i++) {
            double diff = best.get(i).score() - seed.get(i).score();
            if (diff > EPSILON) {
                bestWins++;
            } else if (diff < -EPSILON) {
                seedWins++;
            } else {
                ties++;
            }
        }
        int decisive = bestWins + seedWins;
        return new Comparison(
                bestWins,
                seedWins,
                ties,
                decisive == 0 ? null : PromptComparison.wilson(bestWins, decisive));
    }
}
