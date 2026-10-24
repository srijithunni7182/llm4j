package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.settings.InvalidSettingsException;
import io.github.llm4j.loom.review.settings.LensSelection;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R32, R33 of spec 09: review.properties, its errors, and how lenses are chosen. */
class ReviewSettingsTest {

    @TempDir Path dir;

    @Test
    void nothingDeclaredMeansEightLensesAdvisoryAndNoHeuristics() {
        ReviewSettings s = ReviewSettings.defaults();
        assertThat(s.failOn()).isEmpty();
        assertThat(s.lenses())
                .hasSize(8)
                .doesNotContain(Lens.GOVERNANCE, Lens.BLAST_RADIUS, Lens.TESTABILITY);
        assertThat(s.heuristics()).isFalse();
        assertThat(s.rule("LR01").enabled()).isNull();
    }

    @Test
    void commentsAfterAValueAreIgnoredButNotInsideQuotes() {
        ReviewSettings s =
                ReviewSettings.parse(
                        "review.properties",
                        """
                        # a comment line
                        fail-on = high            # CI gates on high
                        LR04 = off                # turn a rule off
                        data.regions = eu
                        LR05.suppress = Router "free text # on purpose"
                        """);
        assertThat(s.failOn()).contains(Severity.HIGH);
        assertThat(s.rule("LR04").enabled()).isFalse();
        assertThat(s.value("data.regions")).contains("eu");
        assertThat(s.value("LR05.suppress")).contains("Router \"free text # on purpose\"");
    }

    @Test
    void aRuleSeverityCanBeChangedAndARuleTurnedBackOn() {
        ReviewSettings s =
                ReviewSettings.parse("p", "LC01.severity = low\nLP05 = on\nLC07 = off\n");
        assertThat(s.rule("LC01").severityOverride()).contains(Severity.LOW);
        assertThat(s.rule("LP05").enabled()).isTrue();
        assertThat(s.rule("LC07").enabled()).isFalse();
    }

    @Test
    void failOnNoneMeansAdvisoryAndAnUnknownWordIsAnError() {
        assertThat(ReviewSettings.parse("p", "fail-on = none").failOn()).isEmpty();
        assertThatThrownBy(() -> ReviewSettings.parse("p", "fail-on = critical"))
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("p:1")
                .hasMessageContaining("fail-on takes high");
    }

    @Test
    void aMisspeltKeyIsAnErrorThatNamesTheNearestKey() {
        assertThatThrownBy(() -> ReviewSettings.parse("review.properties", "fial-on = high\n"))
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("review.properties:1")
                .hasMessageContaining("did you mean 'fail-on'");
    }

    @Test
    void everyProblemIsReportedNotJustTheFirst() {
        try {
            ReviewSettings.parse(
                    "p",
                    "lenzes = +governance\nfail-on = nope\nnot a line\nLR01.severity = loud\n");
            org.junit.jupiter.api.Assertions.fail("should have thrown");
        } catch (InvalidSettingsException e) {
            assertThat(e.problems()).hasSize(4);
            assertThat(e.problems().get(0)).contains("p:3").contains("expected key = value");
        }
    }

    @Test
    void lensGrammarCoversAllExactAndRelativeForms() {
        Set<Lens> base = LensSelection.defaults();
        assertThat(LensSelection.resolve("all", base)).hasSize(13);
        assertThat(LensSelection.resolve("security,cost", base))
                .containsExactlyInAnyOrder(Lens.SECURITY, Lens.COST);
        Set<Lens> relative = LensSelection.resolve("+oversight,-testability", base);
        assertThat(relative).contains(Lens.OVERSIGHT).doesNotContain(Lens.TESTABILITY).hasSize(9);
    }

    @Test
    void lensGrammarRefusesMixingAndNamesTheNearestLens() {
        Set<Lens> base = LensSelection.defaults();
        assertThatThrownBy(() -> LensSelection.resolve("security,+cost", base))
                .hasMessageContaining("mix of plain and +/-");
        assertThatThrownBy(() -> LensSelection.resolve("securty", base))
                .hasMessageContaining("did you mean 'security'");
        assertThatThrownBy(() -> LensSelection.resolve(" , ", base)).hasMessageContaining("empty");
    }

    @Test
    void lensesInTheFileAndOnTheCommandLineCompose() {
        ReviewSettings s = ReviewSettings.parse("p", "lenses = +governance\n").withLenses("-cost");
        assertThat(s.lensOn(Lens.GOVERNANCE)).isTrue();
        assertThat(s.lensOn(Lens.COST)).isFalse();
    }

    @Test
    void theFileBesideTheScriptIsFoundAndAMissingOneMeansDefaults() throws Exception {
        Path script = dir.resolve("main.loom");
        Files.writeString(script, "x");
        assertThat(ReviewSettings.besides(script).failOn()).isEmpty();
        Files.writeString(dir.resolve("review.properties"), "fail-on = medium\n");
        assertThat(ReviewSettings.besides(script).failOn()).contains(Severity.MEDIUM);
    }

    @Test
    void anUnreadableFileIsAnInvalidSettingsError() {
        assertThatThrownBy(() -> ReviewSettings.load(dir.resolve("nope.properties")))
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("could not be read");
    }

    @Test
    void derivedCopiesChangeOnlyWhatTheyName() {
        ReviewSettings s =
                ReviewSettings.defaults()
                        .withFailOn(java.util.Optional.of(Severity.LOW))
                        .withHeuristics(true);
        assertThat(s.failOn()).contains(Severity.LOW);
        assertThat(s.heuristics()).isTrue();
        assertThat(ReviewSettings.defaults().heuristics()).isFalse();
    }
}
