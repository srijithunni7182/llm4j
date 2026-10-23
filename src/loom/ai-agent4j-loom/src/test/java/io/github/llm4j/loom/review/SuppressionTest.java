package io.github.llm4j.loom.review;

import static io.github.llm4j.loom.review.ReviewEngineTest.f;
import static io.github.llm4j.loom.review.ReviewEngineTest.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.report.JsonReport;
import io.github.llm4j.loom.review.report.MarkdownReport;
import io.github.llm4j.loom.review.report.TextReport;
import io.github.llm4j.loom.review.settings.InvalidSettingsException;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R31 of spec 09: a suppression needs a place and a reason, and nothing is ever hidden. */
class SuppressionTest {

    static final Finding ROUTER =
            f("LR05", Lens.RELIABILITY, Severity.MEDIUM, 12, "agent Router compares free text");
    static final Finding OTHER =
            f("LR05", Lens.RELIABILITY, Severity.MEDIUM, 30, "agent Writer compares free text");

    ReviewReport review(String properties, Finding... findings) {
        ReviewSettings settings = ReviewSettings.parse("review.properties", properties);
        return new ReviewEngine(List.of(source("s", findings)))
                .review(ReviewEngineTest.INPUT, settings);
    }

    @Test
    void aSuppressionByNameSetsAsideOnlyTheFindingThatNamesIt() {
        ReviewReport r = review("LR05.suppress = Router \"free text on purpose\"\n", ROUTER, OTHER);
        assertThat(r.findings()).containsExactly(OTHER);
        assertThat(r.suppressed()).hasSize(1);
        assertThat(r.suppressed().get(0).finding()).isEqualTo(ROUTER);
        assertThat(r.suppressed().get(0).reason()).isEqualTo("free text on purpose");
        assertThat(r.suppressed().get(0).place()).isEqualTo("Router");
    }

    @Test
    void aNameMatchesWholeWordsOnly() {
        ReviewReport r = review("LR05.suppress = Rout \"x\"\n", ROUTER);
        assertThat(r.findings()).containsExactly(ROUTER);
        assertThat(r.notes()).anyMatch(n -> n.message().contains("matched no finding"));
    }

    @Test
    void aSuppressionByLineOrNodeNarrowsToThatPlace() {
        Finding atNode =
                new Finding(
                        "LR05",
                        Lens.RELIABILITY,
                        Severity.LOW,
                        Location.at("main.loom", 50).withNode("n7"),
                        "step n7",
                        "why",
                        "fix",
                        List.of(),
                        Optional.empty(),
                        List.of(),
                        "t");
        ReviewReport r =
                review(
                        "LR05.suppress = line 30 \"checked by hand\"\nLR05.suppress = n7 \"fine\"\n",
                        ROUTER,
                        OTHER,
                        atNode);
        assertThat(r.findings()).containsExactly(ROUTER);
        assertThat(r.suppressed()).hasSize(2);
    }

    @Test
    void aSuppressionAppliesOnlyToItsOwnRule() {
        Finding other = f("LR03", Lens.RELIABILITY, Severity.MEDIUM, 12, "agent Router loops");
        ReviewReport r = review("LR05.suppress = Router \"x\"\n", ROUTER, other);
        assertThat(r.findings()).containsExactly(other);
    }

    @Test
    void aSuppressionWithoutAReasonHidesNothingAndIsAFindingItself() {
        ReviewReport r = review("# header\nLR05.suppress = Router\n", ROUTER);
        assertThat(r.suppressed()).isEmpty();
        assertThat(r.findings()).contains(ROUTER);
        Finding lv08 =
                r.findings().stream()
                        .filter(x -> x.rule().equals("LV08"))
                        .findFirst()
                        .orElseThrow();
        assertThat(lv08.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(lv08.lens()).isEqualTo(Lens.VALIDITY);
        assertThat(lv08.where().file()).isEqualTo("review.properties");
        assertThat(lv08.where().line()).isEqualTo(2);
        assertThat(lv08.fixHint()).contains("LR05.suppress = Router \"why this is fine\"");
    }

    @Test
    void aBlankReasonCountsAsNone() {
        ReviewReport r = review("LR05.suppress = Router \"  \"\n", ROUTER);
        assertThat(r.suppressed()).isEmpty();
        assertThat(r.findings()).extracting(Finding::rule).contains("LV08");
    }

    @Test
    void severityAndRuleSettingsStillApplyBeforeSuppression() {
        ReviewReport r = review("LR05.severity = low\nLR05.suppress = Router \"x\"\n", ROUTER);
        assertThat(r.suppressed().get(0).finding().severity()).isEqualTo(Severity.LOW);
    }

    @Test
    void aMalformedSuppressionIsASettingsErrorWithTheLine() {
        assertThatThrownBy(
                        () ->
                                ReviewSettings.parse(
                                        "review.properties", "LR05.suppress = \"just a reason\"\n"))
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("review.properties:1")
                .hasMessageContaining("takes a scope and a reason");
    }

    @Test
    void everyReportFormatListsWhatWasSetAside() {
        ReviewReport r = review("LR05.suppress = Router \"free text on purpose\"\n", ROUTER, OTHER);
        assertThat(TextReport.write(r))
                .contains("1 suppressed")
                .contains("LR05 at Router")
                .contains("free text on purpose");
        assertThat(MarkdownReport.write(r))
                .contains("## Suppressed")
                .contains("free text on purpose");
        String json = JsonReport.write(r);
        assertThat(json)
                .contains("\"suppressedAt\" : \"Router\"")
                .contains("\"reason\" : \"free text on purpose\"");
    }

    @Test
    void suppressedFindingsDoNotCountAgainstFailOn() {
        ReviewReport r = review("LR05.suppress = Router \"x\"\n", ROUTER);
        assertThat(r.failsAt(Severity.INFO)).isFalse();
    }
}
