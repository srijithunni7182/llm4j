package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.cost.CostEstimate;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 25, R7: LR13 and the cost estimate for a fan-out over a list a model wrote. */
class ForEachFanoutReviewTest extends Lp10Test {

    @TempDir Path folder;

    static final String PLANNER = "agent Planner { model: \"m\"  system: \"You plan.\"  output_schema: { tasks: list<string> } }\nagent Worker { model: \"m\"  system: \"You work.\" }\n";

    static String workflow(String forEachHead) {
        return PLANNER
                + "workflow Main() {\n  delegate \"plan\" to Planner -> plan\n  "
                + forEachHead
                + " {\n    delegate \"{t}\" to Worker -> r retry 1 timeout 30s\n  }\n}\n";
    }

    @Test
    void lr13FiresForAForEachOverAListAnAgentWroteWithNoMax() throws Exception {
        assertThat(review(workflow("for each t in plan.tasks")))
                .anyMatch(f -> f.rule().equals("LR13") && f.severity().word().equals("medium"));
        assertThat(review(workflow("parallel for each t in plan.tasks")))
                .anyMatch(f -> f.rule().equals("LR13"));
    }

    @Test
    void lr13IsQuietWithAMaxAndForAListNoModelWrote() throws Exception {
        assertThat(review(workflow("for each t in plan.tasks max 5"))).noneMatch(f -> f.rule().equals("LR13"));
        String fromParameter =
                PLANNER
                        + "workflow Main(tasks) {\n  for each t in tasks {\n    delegate \"{t}\" to Worker -> r retry 1 timeout 30s\n  }\n}\n";
        assertThat(review(fromParameter)).noneMatch(f -> f.rule().equals("LR13"));
    }

    @Test
    void lr13NamesTheListAndTheWayOut() throws Exception {
        Finding f =
                review(workflow("for each t in plan.tasks")).stream()
                        .filter(x -> x.rule().equals("LR13"))
                        .findFirst()
                        .orElseThrow();

        assertThat(f.title()).contains("plan.tasks");
        assertThat(f.fixHint()).contains("max");
    }

    private CostEstimate estimate(String source) {
        return new CostEstimator(null, null)
                .estimate(ReviewFixtures.input(folder, source), ReviewSettings.parse("review.properties", ""));
    }

    @Test
    void theWorstCaseOfAnUnboundedModelListIsUnknownNotAMadeUpNumber() {
        var w = estimate(workflow("for each t in plan.tasks")).workflows().get(0);

        assertThat(w.unbounded()).isTrue();
        assertThat(String.join(" ", w.notes())).contains("worst case unknown").contains("no bound");
    }

    @Test
    void aMaxGivesAWorstCaseThatIsExactlyTheBound() {
        var bounded = estimate(workflow("for each t in plan.tasks max 3")).workflows().get(0);

        assertThat(bounded.unbounded()).isFalse();
        // the planner call plus at most three worker calls of two attempts each (retry 1)
        assertThat(bounded.worst().calls()).isEqualTo(7);
    }
}
