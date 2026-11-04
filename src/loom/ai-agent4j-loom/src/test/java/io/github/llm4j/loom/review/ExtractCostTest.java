package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.cost.CostEstimate;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 24, D38: an extract step is a model call, so the cost estimate lists it as its own line. */
class ExtractCostTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            extractor Invoice { model: "m" fields { vendor: string, total: number } }
            workflow Main(t) {
                extract Invoice from t -> r retry 2
            }
            """;

    private CostEstimate estimate() {
        return new CostEstimator(null, null)
                .estimate(ReviewFixtures.input(dir, SCRIPT), ReviewSettings.parse("review.properties", ""));
    }

    @Test
    void anExtractStepIsOneModelCallOnItsOwnLine() {
        var w = estimate().workflows().get(0);

        assertThat(w.steps()).hasSize(1);
        assertThat(w.steps().get(0).label()).isEqualTo("extract Invoice");
        assertThat(w.steps().get(0).model()).isEqualTo("m");
        assertThat(w.typical().calls()).isEqualTo(1);
    }

    @Test
    void theWorstCaseCountsEveryRetryAndTheOneCorrection() {
        var w = estimate().workflows().get(0);

        // retry 2 allows 3 attempts, plus the one correction that is always allowed
        assertThat(w.worst().calls()).isEqualTo(4);
    }
}
