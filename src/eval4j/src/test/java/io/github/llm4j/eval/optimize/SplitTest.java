package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SplitTest {

    static List<EvalScenario> scenarios(int n) {
        List<EvalScenario> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new EvalScenario("s" + i, "input " + i, null, null, null, null, null));
        }
        return out;
    }

    @Test
    void ratiosPartitionEverythingDisjointlyAndDeterministically() {
        List<EvalScenario> all = scenarios(50);

        DataSplit a = Split.ratios(0.5, 0.3, 0.2).seed(42).apply(all);
        DataSplit b = Split.ratios(0.5, 0.3, 0.2).seed(42).apply(all);
        DataSplit other = Split.ratios(0.5, 0.3, 0.2).seed(43).apply(all);

        assertThat(a.train()).hasSize(25);
        assertThat(a.validation()).hasSize(15);
        assertThat(a.test()).hasSize(10);
        assertThat(a).isEqualTo(b);
        assertThat(a.train()).isNotEqualTo(other.train());
        Set<EvalScenario> union = new HashSet<>(a.train());
        union.addAll(a.validation());
        union.addAll(a.test());
        assertThat(union).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void propertyInvariantsHoldForRandomSizesAndRatios() {
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            int n = 20 + random.nextInt(200);
            double train = 0.3 + random.nextDouble() * 0.4;
            double validation = (1 - train) * (0.3 + random.nextDouble() * 0.4);
            double test = 1 - train - validation;
            DataSplit split =
                    Split.ratios(train, validation, test)
                            .seed(i)
                            .minSplitSize(1)
                            .apply(scenarios(n));
            assertThat(split.train().size() + split.validation().size() + split.test().size())
                    .isEqualTo(n);
        }
    }

    @Test
    void zeroTestRatioNeedsAllowNoTest() {
        assertThatThrownBy(() -> Split.ratios(0.6, 0.4, 0).apply(scenarios(30)))
                .isInstanceOf(OptimizerConfigurationException.class)
                .hasMessageContaining("test split is empty")
                .hasMessageContaining("allowNoTest");

        DataSplit split = Split.ratios(0.6, 0.4, 0).allowNoTest().apply(scenarios(30));
        assertThat(split.test()).isEmpty();
        assertThat(split.train().size() + split.validation().size()).isEqualTo(30);
    }

    @Test
    void ratiosMustSumToOneAndBeNonNegative() {
        assertThatThrownBy(() -> Split.ratios(0.5, 0.3, 0.3).apply(scenarios(30)))
                .isInstanceOf(OptimizerConfigurationException.class)
                .hasMessageContaining("sum to 1.0");
        assertThatThrownBy(() -> Split.ratios(1.2, -0.1, -0.1).apply(scenarios(30)))
                .isInstanceOf(OptimizerConfigurationException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void smallSplitsAreRejectedWithCountsAndAFix() {
        assertThatThrownBy(() -> Split.ratios(0.5, 0.3, 0.2).apply(scenarios(20)))
                .isInstanceOf(OptimizerConfigurationException.class)
                .hasMessageContaining("test split has 4 scenarios")
                .hasMessageContaining("at least 5")
                .hasMessageContaining("DatasetSynthesizer");
        assertThat(Split.ratios(0.5, 0.3, 0.2).minSplitSize(4).apply(scenarios(20)).test())
                .hasSize(4);
    }

    @Test
    void explicitSplitsAreValidatedForSizeAndOverlap() {
        List<EvalScenario> all = scenarios(18);
        DataSplit ok =
                Split.explicit(all.subList(0, 6), all.subList(6, 12), all.subList(12, 18))
                        .apply(List.of());
        assertThat(ok.test()).hasSize(6);

        assertThatThrownBy(
                        () ->
                                Split.explicit(
                                                all.subList(0, 6),
                                                all.subList(5, 11),
                                                all.subList(12, 18))
                                        .apply(List.of()))
                .isInstanceOf(OptimizerConfigurationException.class)
                .hasMessageContaining("more than one split");
        assertThatThrownBy(
                        () ->
                                Split.explicit(
                                                all.subList(0, 6),
                                                all.subList(6, 8),
                                                all.subList(12, 18))
                                        .apply(List.of()))
                .isInstanceOf(OptimizerConfigurationException.class)
                .hasMessageContaining("validation split has 2");
    }

    @Test
    void minSplitSizeMustBePositive() {
        assertThatThrownBy(() -> Split.ratios(0.5, 0.3, 0.2).minSplitSize(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
