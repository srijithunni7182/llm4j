package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Fix;
import io.github.llm4j.loom.review.model.FixSafety;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.model.TextEdit;
import io.github.llm4j.loom.review.report.JsonReport;
import io.github.llm4j.loom.review.report.MarkdownReport;
import io.github.llm4j.loom.review.report.TextReport;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R5, R9 of spec 09: the JSON contract, and text that cannot change what a terminal does. */
class ReportFormatsTest {

    static Finding finding(String rule, Severity sev, String title, String why) {
        return new Finding(
                rule,
                Lens.SECURITY,
                sev,
                Location.at("main.loom", 46).withNode("n7"),
                title,
                why,
                "fix it",
                List.of("LLM06"),
                Optional.of(
                        new Fix(
                                FixSafety.SAFE,
                                "Add approve",
                                List.of(
                                        new TextEdit(
                                                "main.loom",
                                                47,
                                                1,
                                                47,
                                                1,
                                                "    approve: Mailer\n")))),
                List.of("LA02"),
                "audit");
    }

    static ReviewReport report(Finding... findings) {
        return new ReviewReport(
                "main.loom",
                List.of(findings),
                List.of(),
                List.of(Note.notEvaluated("LY03", "data.regions is not set")),
                List.of(Lens.SECURITY, Lens.COST),
                List.of(Lens.GOVERNANCE));
    }

    @Test
    void theJsonIsVersionedAndCarriesEveryFieldTheEditorNeeds() throws Exception {
        JsonNode j =
                new ObjectMapper()
                        .readTree(
                                JsonReport.write(
                                        report(
                                                finding("LA01", Severity.HIGH, "T", "W"),
                                                finding("LR01", Severity.LOW, "T2", "W2"))));
        assertThat(j.get("version").asInt()).isEqualTo(1);
        assertThat(j.get("file").asText()).isEqualTo("main.loom");
        assertThat(j.get("summary").get("total").asInt()).isEqualTo(2);
        assertThat(j.get("summary").get("high").asInt()).isEqualTo(1);
        assertThat(j.get("summary").get("medium").asInt()).isZero();
        assertThat(j.get("summary").get("low").asInt()).isEqualTo(1);
        assertThat(j.get("summary").get("lenses").get("ran").get(0).asText()).isEqualTo("security");
        assertThat(j.get("summary").get("lenses").get("off").get(0).asText())
                .isEqualTo("governance");
        JsonNode f = j.get("findings").get(0);
        assertThat(f.get("rule").asText()).isEqualTo("LA01");
        assertThat(f.get("lens").asText()).isEqualTo("security");
        assertThat(f.get("severity").asText()).isEqualTo("high");
        assertThat(f.get("location").get("line").asInt()).isEqualTo(46);
        assertThat(f.get("location").get("node").asText()).isEqualTo("n7");
        assertThat(f.get("owasp").get(0).asText()).isEqualTo("LLM06");
        assertThat(f.get("related").get(0).asText()).isEqualTo("LA02");
        assertThat(f.get("fix").get("safety").asText()).isEqualTo("safe");
        assertThat(f.get("fix").get("edits").get(0).get("newText").asText())
                .isEqualTo("    approve: Mailer\n");
        assertThat(j.get("notes").get(0).get("kind").asText()).isEqualTo("not_evaluated");
        assertThat(j.get("suppressed")).isEmpty();
    }

    @Test
    void hostileTextRoundTripsThroughJsonUnchanged() throws Exception {
        String nasty = "quote \" backslash \\ newline \n tab \t emoji 😀 rtl ‮ end </script>";
        JsonNode j =
                new ObjectMapper()
                        .readTree(
                                JsonReport.write(
                                        report(finding("LA01", Severity.HIGH, nasty, nasty))));
        assertThat(j.get("findings").get(0).get("title").asText()).isEqualTo(nasty);
    }

    @Test
    void textShowsTheSummaryTheFindingsTheNotesAndTheAdvisoryLine() {
        String t =
                TextReport.write(
                        report(
                                finding("LA01", Severity.HIGH, "Trifecta", "because"),
                                finding("LR01", Severity.LOW, "Slow", "w")));
        assertThat(t).contains("main.loom   1 high  0 medium  1 low");
        assertThat(t).contains("Lenses   2 on · 1 off (weave review --lens all)");
        assertThat(t)
                .contains("HIGH")
                .contains("LA01")
                .contains("Trifecta")
                .contains("why: because")
                .contains("fix: fix it");
        assertThat(t).contains("auto-fix (safe): Add approve");
        assertThat(t).contains("LY03: data.regions is not set");
        assertThat(t).contains("Advisory: open findings never block run, package or deploy.");
        assertThat(t.indexOf("HIGH")).isLessThan(t.indexOf("LOW"));
    }

    @Test
    void textSaysSoWhenThereAreNoOpenFindings() {
        assertThat(TextReport.write(report())).contains("no open findings");
    }

    @Test
    void controlCharactersFromAScriptNeverReachTheTerminal() {
        String escape = "\u001b[31mred\u001b[0m\u0007bell\r\nnext";
        String text = TextReport.write(report(finding("LA01", Severity.HIGH, escape, escape)));
        assertThat(text).doesNotContain("\u001b").doesNotContain("\u0007").doesNotContain("\r");
        assertThat(text).contains("red").contains("bell").contains("next");
        assertThat(MarkdownReport.write(report(finding("LA01", Severity.HIGH, escape, escape))))
                .doesNotContain("\u001b");
    }

    @Test
    void markdownGroupsBySeverityAndEscapesItsOwnMarkers() {
        String md =
                MarkdownReport.write(
                        report(
                                finding("LA01", Severity.HIGH, "uses `code` and <b>tags</b>", "w"),
                                finding("LR01", Severity.LOW, "plain", "w")));
        assertThat(md)
                .contains("# Review of main.loom")
                .contains("## High (1)")
                .contains("## Low (1)");
        assertThat(md).contains("### `LA01` uses \\`code\\` and &lt;b&gt;tags&lt;/b&gt;");
        assertThat(md).contains("- OWASP LLM: LLM06").contains("Advisory:");
        assertThat(md).contains("Optional lenses off: Model governance");
    }
}
