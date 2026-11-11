package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.cost.RunProfile;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** docs/specs/32-a2a-integration R35: a remote step is shown as outside your budgets, never as free and never priced as a local agent. */
class RemoteCostTest {

    @TempDir Path dir;

    static final String SCRIPT = """
            agent Local { model: "gemini-2.0-flash" system: "s" }
            agent_channel Desk { audience: staff }
            remote Supplier {
                card: "https://supplier.example.com/card.json"
                agent_channel: Desk
                timeout: 2h
            }
            workflow Main(t) {
                delegate "local {t}" to Local -> a
                delegate "quote {t}" to Supplier -> quote retry 1
                note "{a} {quote}"
            }
            """;

    @Test
    void aRemoteStepIsNotPricedAsALocalAgentAndTheEstimateSaysItIsOutsideTheBudgets() {
        var estimate = new CostEstimator(null, null).estimate(ReviewFixtures.input(dir, SCRIPT), ReviewSettings.parse("review.properties", ""));
        var w = estimate.workflows().get(0);
        assertThat(w.steps()).extracting(s -> s.label()).containsExactly("delegate Local");
        assertThat(w.typical().calls()).isEqualTo(1);
        assertThat(w.notes()).anyMatch(n -> n.contains("Supplier") && n.contains("outside your budgets"));
    }

    @Test
    void theTimeProfileUsesTheDeclaredDeadlineAndNamesTheRemoteAsSomethingItDoesNotMeasure() {
        var profile = RunProfile.of(ReviewFixtures.input(dir, SCRIPT), ReviewSettings.parse("review.properties", "")).get("Main");
        assertThat(profile.time().worstMillis()).isGreaterThanOrEqualTo(2 * 2 * 3_600_000L); // two attempts, each up to the declared 2h
        assertThat(profile.time().waits()).anyMatch(w -> w.contains("Supplier") && w.contains("remote"));
        assertThat(profile.effects().sends().worst()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void theWholeReviewCopesWithARemoteAndAnAskAndSaysNothingAboutAnAgentThatIsNotDefined() throws Exception {
        String source = SCRIPT + "workflow Other(t) { ask Desk \"Which?\" -> r\n note \"{r}\" }\n";
        Path file = dir.resolve("main.loom");
        java.nio.file.Files.writeString(file, source);
        var script = new io.github.llm4j.loom.execution.LoomLoader().load(file.toString());
        var input = new io.github.llm4j.loom.review.engine.ReviewInput("main.loom", dir, script, java.util.Map.of(), java.util.List.of(), new io.github.llm4j.loom.graph.GraphService().graph(file), source);
        var report = new io.github.llm4j.loom.review.engine.ReviewEngine(java.util.List.of(new io.github.llm4j.loom.review.pack.DataRuleSource(java.util.List.of(io.github.llm4j.loom.review.Reviews.corePack()))))
                .review(input, ReviewSettings.defaults().withLenses("all"));
        assertThat(report.toString()).doesNotContainIgnoringCase("not defined").doesNotContain("Supplier is not");
    }
}
