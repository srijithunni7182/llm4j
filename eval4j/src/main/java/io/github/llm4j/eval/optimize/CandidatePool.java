package io.github.llm4j.eval.optimize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * The candidates found so far with their per-validation-scenario scores. Answers "which candidate
 * should we improve next?" (Pareto-weighted) and "which is best?".
 */
final class CandidatePool {

    /** A candidate with its validation scores, one per validation scenario, in split order. */
    record Entry(Candidate candidate, double[] validationScores, boolean guardrailViolation) {
        double mean() {
            double sum = 0;
            for (double s : validationScores) {
                sum += s;
            }
            return validationScores.length == 0 ? 0 : sum / validationScores.length;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Set<Candidate> known = new HashSet<>();

    /** Adds a candidate; returns false (adding nothing) if an equal one is already present. */
    boolean add(Candidate candidate, double[] validationScores) {
        return add(candidate, validationScores, false);
    }

    boolean add(Candidate candidate, double[] validationScores, boolean guardrailViolation) {
        if (!known.add(candidate)) {
            return false;
        }
        entries.add(new Entry(candidate, validationScores.clone(), guardrailViolation));
        return true;
    }

    boolean contains(Candidate candidate) {
        return known.contains(candidate);
    }

    int size() {
        return entries.size();
    }

    List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** Selection weights per entry (see {@link ParetoSelector}). */
    double[] weights() {
        double[][] matrix = new double[entries.size()][];
        for (int i = 0; i < matrix.length; i++) {
            matrix[i] = entries.get(i).validationScores();
        }
        return ParetoSelector.weights(matrix);
    }

    /** Number of entries on the Pareto frontier (weight above zero). */
    int frontierSize() {
        int count = 0;
        for (double w : weights()) {
            if (w > 0) {
                count++;
            }
        }
        return count;
    }

    /** Whether {@code candidate} is currently on the frontier. */
    boolean onFrontier(Candidate candidate) {
        double[] weights = weights();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).candidate().equals(candidate)) {
                return weights[i] > 0;
            }
        }
        return false;
    }

    /** Picks a parent, weighted by scenarios won; falls back to the first entry. */
    Candidate pick(SplittableRandom random) {
        int index = ParetoSelector.pick(weights(), random.nextDouble());
        return entries.get(Math.max(index, 0)).candidate();
    }

    /** Highest validation mean; ties prefer the shorter candidate, then the earlier one. */
    Entry best() {
        Entry best = null;
        for (Entry entry : entries) {
            if (best == null
                    || entry.mean() > best.mean() + ParetoSelector.EPSILON
                    || (Math.abs(entry.mean() - best.mean()) <= ParetoSelector.EPSILON
                            && entry.candidate().totalLength() < best.candidate().totalLength())) {
                best = entry;
            }
        }
        return best;
    }
}
