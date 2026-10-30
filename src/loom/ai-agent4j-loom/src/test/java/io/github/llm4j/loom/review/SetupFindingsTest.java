package io.github.llm4j.loom.review;

import static io.github.llm4j.loom.review.ReviewEngineTest.f;
import static io.github.llm4j.loom.review.ReviewEngineTest.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.engine.Setup;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.report.JsonReport;
import io.github.llm4j.loom.review.report.TextReport;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 22 R9: a finding is a script finding only if editing the script can clear it. A missing key
 * is a fact about the machine, so it is a setup finding: listed apart, never counted as open, and
 * never what {@code --fail-on} fails on, unless the person asks for it.
 */
class SetupFindingsTest {

    private static Finding key(int line, String agent) {
        return f(
                "LV02",
                Lens.VALIDITY,
                Severity.HIGH,
                line,
                "agent " + agent + ": model gemini-2.5-flash needs GEMINI_API_KEY in the environment or the secret store");
    }

    private static Finding promptFile(int line) {
        return f("LV02", Lens.VALIDITY, Severity.HIGH, line, "agent Writer: prompt file prompts/writer.md does not exist");
    }

    private static ReviewReport review(Finding... findings) {
        return new ReviewEngine(List.of(source("s", findings)))
                .review(ReviewEngineTest.INPUT, ReviewSettings.defaults());
    }

    @Test
    void aMissingKeyIsASetupFindingNotAnOpenOne() {
        ReviewReport report = review(key(6, "Researcher"), key(7, "Writer"));

        assertThat(report.findings()).isEmpty();
        assertThat(report.setup()).hasSize(2);
        assertThat(report.count(Severity.HIGH)).isZero();
        assertThat(report.failsAt(Severity.HIGH)).isFalse();
    }

    @Test
    void aMissingFileTheScriptNamesIsStillAScriptFinding() {
        ReviewReport report = review(key(6, "Researcher"), promptFile(8));

        assertThat(report.findings()).extracting(Finding::title).containsExactly("agent Writer: prompt file prompts/writer.md does not exist");
        assertThat(report.setup()).hasSize(1);
        assertThat(report.failsAt(Severity.HIGH)).isTrue();
    }

    @Test
    void theRuleIsAboutTheFindingNotTheLine() {
        assertThat(Setup.is(key(1, "A"))).isTrue();
        assertThat(Setup.is(promptFile(1))).isFalse();
        assertThat(Setup.is(f("LR01", Lens.RELIABILITY, Severity.HIGH, 1, "needs GEMINI_API_KEY in the environment"))).isFalse();
    }

    @Test
    void includingSetupBringsThemBackAsOrdinaryFindings() {
        ReviewReport merged = review(key(6, "Researcher"), promptFile(8)).withSetupAsFindings();

        assertThat(merged.findings()).hasSize(2);
        assertThat(merged.setup()).isEmpty();
        assertThat(merged.failsAt(Severity.HIGH)).isTrue();
        assertThat(merged.findings().get(0).where().line()).isLessThan(merged.findings().get(1).where().line());
    }

    @Test
    void theTextSaysWhatToSetUpAndKeepsItOutOfTheCounts() {
        String text = TextReport.write(review(key(6, "Researcher"), key(7, "Writer")));

        assertThat(text).contains("no open findings").contains("Setup");
        assertThat(text).contains("GEMINI_API_KEY").contains("not a finding in the script");
        assertThat(text).doesNotContain("2 high");
    }

    @Test
    void theJsonListsThemApartFromTheFindings() throws Exception {
        JsonNode json = new ObjectMapper().readTree(JsonReport.write(review(key(6, "Researcher"), promptFile(8))));

        assertThat(json.path("findings")).hasSize(1);
        assertThat(json.path("setup")).hasSize(1);
        assertThat(json.path("summary").path("setup").asInt()).isEqualTo(1);
        assertThat(json.path("summary").path("high").asInt()).isEqualTo(1);
    }
}
