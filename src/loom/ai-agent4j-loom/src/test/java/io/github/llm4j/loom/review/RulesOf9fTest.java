package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.pack.DataRuleSource;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R37, R38 of spec 09: heuristic rules are off by default, and a rule that needs a declared fact
 * says so instead of passing.
 */
class RulesOf9fTest {

    @TempDir Path dir;

    ReviewReport review(String script, String properties) {
        return new ReviewEngine(List.of(new DataRuleSource(List.of(Reviews.corePack()))))
                .review(
                        ReviewFixtures.input(dir, script),
                        ReviewSettings.parse("review.properties", properties));
    }

    static final String BRANCH =
            "agent Judge { model: \"m\"  system: \"You decide whether the text is fine.\" }\n"
                    + "workflow Main(topic) {\n  delegate \"Is this fine? {topic}\" to Judge -> verdict\n  alt (verdict == \"yes\") { note \"a\" } else { note \"b\" }\n}\n";

    @Test
    void theHeuristicRulesStayQuietUntilHeuristicsAreOnOrTheRuleIsNamed() {
        assertThat(review(BRANCH, "").findings())
                .extracting(Finding::rule)
                .doesNotContain("LP02", "LP05", "LC07");
        assertThat(review(BRANCH, "heuristics = on\n").findings())
                .extracting(Finding::rule)
                .contains("LP02");
        assertThat(review(BRANCH, "LP02 = on\n").findings())
                .extracting(Finding::rule)
                .contains("LP02");
    }

    @Test
    void theDurabilityRuleThatNeedsTheJournalSettingIsNotEvaluatedUntilItIsDeclared() {
        String pause =
                "agent W { model: \"m\"  system: \"s\" }\nworkflow Main(q) {\n  delegate \"d {q}\" to W -> draft\n  human_prompt \"ok?\" -> ok\n}\n";
        ReviewReport r = review(pause, "");
        assertThat(r.findings()).extracting(Finding::rule).doesNotContain("LD01");
        assertThat(r.notes())
                .anyMatch(
                        n ->
                                n.kind() == Note.Kind.NOT_EVALUATED
                                        && n.rule().equals("LD01")
                                        && n.message().contains("journal is not declared"));
        assertThat(review(pause, "journal = none\n").findings())
                .extracting(Finding::rule)
                .contains("LD01");
        assertThat(review(pause, "journal = file\n").findings())
                .extracting(Finding::rule)
                .doesNotContain("LD01");
    }

    @Test
    void theLoopThresholdIsDeclaredInTheProjectAndDefaultsToTwenty() {
        String loop =
                "agent W { model: \"m\"  system: \"s\" }\nworkflow Main(t) {\n  loop until (d == \"good\") max 50 {\n    delegate \"x {t}\" to W -> d\n  }\n}\n";
        assertThat(review(loop, "").findings()).extracting(Finding::rule).contains("LO04");
        assertThat(review(loop, "data.loop.incident = 60\n").findings())
                .extracting(Finding::rule)
                .doesNotContain("LO04");
    }

    @Test
    void personalValuesAreFoundByNameOrByWhatTheProjectDeclares() {
        String s =
                "agent R { model: \"m\"  system: \"s\" }\nworkflow Main(ticket_body, customer_email, topic) {\n  delegate \"a {topic}\" to R -> one\n  delegate \"b {ticket_body}\" to R -> two\n  delegate \"c {customer_email}\" to R -> three\n}\n";
        assertThat(review(s, "").findings().stream().filter(f -> f.rule().equals("LY01")).count())
                .isEqualTo(1);
        assertThat(
                        review(s, "data.personal = ticket_body\n").findings().stream()
                                .filter(f -> f.rule().equals("LY01"))
                                .count())
                .isEqualTo(2);
    }

    @Test
    void anEffectfulAgentRaisesTheSeverityOfAPersonalDataFinding() {
        String s =
                "tool Hook { use: webhook  url: env.HOOK_URL }\nagent N { model: \"m\"  system: \"s\"  tools: [Hook] }\nworkflow Main(customer_email) {\n  delegate \"tell {customer_email}\" to N -> sent\n}\n";
        Finding f =
                review(s, "").findings().stream()
                        .filter(x -> x.rule().equals("LY01"))
                        .findFirst()
                        .orElseThrow();
        assertThat(f.severity()).isEqualTo(io.github.llm4j.loom.review.model.Severity.HIGH);
    }
}
