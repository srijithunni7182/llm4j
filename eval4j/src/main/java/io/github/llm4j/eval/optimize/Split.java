package io.github.llm4j.eval.optimize;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * How scenarios are divided into train / validation / test. Use {@link #ratios} to shuffle and
 * partition one dataset (deterministically, by seed) or {@link #explicit} for datasets that are
 * already separated. Splits are validated: each must reach {@code minSplitSize} (default 5), and a
 * missing test split is only allowed with {@link #allowNoTest()}.
 */
public final class Split {

    private static final double EPSILON = 1e-9;

    private final double[] ratios;
    private final DataSplit explicit;
    private long seed;
    private int minSplitSize = 5;
    private boolean allowNoTest;

    private Split(double[] ratios, DataSplit explicit) {
        this.ratios = ratios;
        this.explicit = explicit;
    }

    public static Split ratios(double train, double validation, double test) {
        return new Split(new double[] {train, validation, test}, null);
    }

    public static Split explicit(
            List<EvalScenario> train, List<EvalScenario> validation, List<EvalScenario> test) {
        return new Split(null, new DataSplit(train, validation, test));
    }

    /** Seed for the shuffle used by {@link #ratios}. */
    public Split seed(long seed) {
        this.seed = seed;
        return this;
    }

    public Split minSplitSize(int minSplitSize) {
        if (minSplitSize < 1) {
            throw new IllegalArgumentException("minSplitSize must be at least 1");
        }
        this.minSplitSize = minSplitSize;
        return this;
    }

    /** Permit an empty test split; the run's result can then never be marked as generalized. */
    public Split allowNoTest() {
        this.allowNoTest = true;
        return this;
    }

    boolean isExplicit() {
        return explicit != null;
    }

    /** Partitions {@code scenarios}, or validates the explicit split. */
    public DataSplit apply(List<EvalScenario> scenarios) {
        DataSplit split = explicit != null ? explicit : partition(scenarios);
        validate(split);
        return split;
    }

    private DataSplit partition(List<EvalScenario> scenarios) {
        for (double ratio : ratios) {
            if (ratio < 0 || Double.isNaN(ratio)) {
                throw new OptimizerConfigurationException(
                        "split ratios cannot be negative, got: " + describeRatios());
            }
        }
        double sum = ratios[0] + ratios[1] + ratios[2];
        if (Math.abs(sum - 1.0) > EPSILON) {
            throw new OptimizerConfigurationException(
                    "split ratios must sum to 1.0 but were "
                            + describeRatios()
                            + " (sum "
                            + sum
                            + ")");
        }
        List<EvalScenario> shuffled = new ArrayList<>(scenarios);
        Collections.shuffle(shuffled, new Random(seed));
        int n = shuffled.size();
        int train = (int) Math.floor(n * ratios[0] + EPSILON);
        int validation = (int) Math.floor(n * ratios[1] + EPSILON);
        int test = n - train - validation;
        if (ratios[2] == 0) {
            validation = n - train;
            test = 0;
        }
        return new DataSplit(
                shuffled.subList(0, train),
                shuffled.subList(train, train + validation),
                shuffled.subList(train + validation, train + validation + test));
    }

    private void validate(DataSplit split) {
        check("train", split.train().size());
        check("validation", split.validation().size());
        if (split.test().isEmpty()) {
            if (!allowNoTest) {
                throw new OptimizerConfigurationException(
                        "the test split is empty. A sealed test split is how the optimizer tells a real"
                                + " improvement from overfitting; provide one or call allowNoTest()"
                                + " (the result will then never be marked as generalized).");
            }
        } else {
            check("test", split.test().size());
        }
        Set<EvalScenario> seen = new HashSet<>();
        for (List<EvalScenario> part : List.of(split.train(), split.validation(), split.test())) {
            Set<EvalScenario> inThisPart = new HashSet<>(part);
            for (EvalScenario scenario : inThisPart) {
                if (!seen.add(scenario)) {
                    throw new OptimizerConfigurationException(
                            "scenario \""
                                    + scenario
                                    + "\" appears in more than one split; overlapping splits leak"
                                    + " information and invalidate the held-out check.");
                }
            }
        }
    }

    private void check(String name, int size) {
        if (size < minSplitSize) {
            throw new OptimizerConfigurationException(
                    "the "
                            + name
                            + " split has "
                            + size
                            + " scenarios but at least "
                            + minSplitSize
                            + " are required. Add more scenarios (DatasetSynthesizer can generate"
                            + " them), change the ratios, or lower minSplitSize (results get noisier).");
        }
    }

    private String describeRatios() {
        return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", ratios[0], ratios[1], ratios[2]);
    }
}
