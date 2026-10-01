package io.github.llm4j.eval.judge;

/** Pure scoring formulas for the contextual RAG metrics, kept free of I/O so they test exactly. */
final class RagScoring {

    private RagScoring() {}

    /** Fraction of chunks flagged relevant; 0.0 for an empty list. */
    static double relevancy(boolean[] relevant) {
        if (relevant.length == 0) {
            return 0.0;
        }
        int count = 0;
        for (boolean r : relevant) {
            if (r) {
                count++;
            }
        }
        return count / (double) relevant.length;
    }

    /**
     * Weighted cumulative precision: {@code (1/R) * sum_k (relevantUpTo(k) / k) * rel_k}, with R
     * the number of relevant chunks. Rewards ranking relevant chunks first; 0.0 when none are
     * relevant.
     */
    static double precision(boolean[] relevant) {
        int totalRelevant = 0;
        for (boolean r : relevant) {
            if (r) {
                totalRelevant++;
            }
        }
        if (totalRelevant == 0) {
            return 0.0;
        }
        double sum = 0.0;
        int seen = 0;
        for (int k = 1; k <= relevant.length; k++) {
            if (relevant[k - 1]) {
                seen++;
                sum += seen / (double) k;
            }
        }
        return sum / totalRelevant;
    }

    /** Fraction of statements attributable to the context; 1.0 when there is nothing to verify. */
    static double recall(int supported, int total) {
        return total == 0 ? 1.0 : supported / (double) total;
    }
}
