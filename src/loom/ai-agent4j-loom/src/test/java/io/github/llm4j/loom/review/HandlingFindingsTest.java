package io.github.llm4j.loom.review;

import static io.github.llm4j.loom.review.ReviewEngineTest.f;
import static io.github.llm4j.loom.review.ReviewEngineTest.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.handle.AcceptWriter;
import io.github.llm4j.loom.review.handle.Baseline;
import io.github.llm4j.loom.review.handle.NoiseReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.settings.InvalidSettingsException;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R41 to R47 of spec 09: fingerprints, accepts with scopes and expiry, the baseline, the noise
 * report.
 */
class HandlingFindingsTest {

    @TempDir Path dir;

    static Finding about(String rule, int line, String title, String anchor) {
        return f(rule, Lens.RELIABILITY, Severity.MEDIUM, line, title).withAnchor(anchor);
    }

    ReviewReport review(ReviewSettings settings, Finding... findings) {
        return new ReviewEngine(List.of(source("s", findings)))
                .review(ReviewEngineTest.INPUT, settings);
    }

    ReviewSettings settings(String properties) {
        return ReviewSettings.parse("review.properties", properties)
                .withToday(LocalDate.parse("2026-10-23"));
    }

    // ---- fingerprints (R41)

    @Test
    void aFingerprintDoesNotChangeWhenTheFindingMovesOrNumbersInItsTitleChange() {
        Finding a =
                about(
                        "LR01",
                        10,
                        "step delegate Replier has no retry (2 tools)",
                        "step:Main/delegate#1");
        Finding moved =
                about(
                        "LR01",
                        55,
                        "step delegate Replier has no retry (3 tools)",
                        "step:Main/delegate#1");
        assertThat(moved.fingerprint()).isEqualTo(a.fingerprint());
        assertThat(a.fingerprint()).hasSize(16).matches("[0-9a-f]+");
    }

    @Test
    void aDifferentThingOrRuleOrWordingHasADifferentFingerprint() {
        Finding a = about("LR01", 10, "no retry", "step:Main/delegate#1");
        assertThat(about("LR01", 10, "no retry", "step:Main/delegate#2").fingerprint())
                .isNotEqualTo(a.fingerprint());
        assertThat(about("LR02", 10, "no retry", "step:Main/delegate#1").fingerprint())
                .isNotEqualTo(a.fingerprint());
        assertThat(about("LR01", 10, "no timeout", "step:Main/delegate#1").fingerprint())
                .isNotEqualTo(a.fingerprint());
    }

    // ---- accept scopes (R42, R43)

    @Test
    void aFindingScopedAcceptCoversOnlyThatFinding() {
        Finding a = about("LR01", 10, "agent Router has no retry", "agent:Router");
        Finding b = about("LR01", 20, "agent Writer has no retry", "agent:Writer");
        ReviewReport r =
                review(
                        settings("LR01.accept = finding " + a.fingerprint() + " \"checked\"\n"),
                        a,
                        b);
        assertThat(r.findings()).containsExactly(b);
        assertThat(r.suppressed()).hasSize(1);
        assertThat(r.suppressed().get(0).place()).isEqualTo("finding " + a.fingerprint());
    }

    @Test
    void aFingerprintAcceptSurvivesTheFindingMoving() {
        Finding a = about("LR01", 10, "agent Router has no retry", "agent:Router");
        Finding moved = about("LR01", 90, "agent Router has no retry", "agent:Router");
        ReviewReport r =
                review(
                        settings("LR01.accept = finding " + a.fingerprint() + " \"checked\"\n"),
                        moved);
        assertThat(r.findings()).isEmpty();
        assertThat(r.suppressed()).hasSize(1);
    }

    @Test
    void anAcceptDoesNotHideADifferentFindingOfTheSameRule() {
        Finding a = about("LR01", 10, "agent Router has no retry", "agent:Router");
        Finding other = about("LR01", 10, "agent Writer has no retry", "agent:Writer");
        ReviewReport r =
                review(
                        settings("LR01.accept = finding " + a.fingerprint() + " \"checked\"\n"),
                        other);
        assertThat(r.findings()).containsExactly(other);
    }

    @Test
    void thingScriptAndProjectScopesWidenWhatIsCovered() {
        Finding router = about("LR01", 10, "agent Router has no retry", "agent:Router");
        Finding writer = about("LR01", 20, "agent Writer has no retry", "agent:Writer");
        assertThat(review(settings("LR01.accept = on Router \"x\"\n"), router, writer).findings())
                .containsExactly(writer);
        assertThat(
                        review(settings("LR01.accept = script main.loom \"x\"\n"), router, writer)
                                .findings())
                .isEmpty();
        assertThat(
                        review(settings("LR01.accept = script other.loom \"x\"\n"), router, writer)
                                .findings())
                .hasSize(2);
        assertThat(review(settings("LR01.accept = project \"x\"\n"), router, writer).findings())
                .isEmpty();
    }

    @Test
    void aNameScopeAlsoCoversFindingsAnchoredToTheNamedWorkflowOrAgent() {
        Finding step = about("LR01", 10, "delegate Searcher has no retry", "step:Main/delegate#1");
        Finding otherWorkflow =
                about("LR01", 30, "delegate Searcher has no retry", "step:Other/delegate#1");
        ReviewReport r = review(settings("LR01.accept = on Main \"x\"\n"), step, otherWorkflow);
        assertThat(r.findings()).containsExactly(otherWorkflow);
    }

    @Test
    void anAcceptNeverCoversAnotherRule() {
        Finding a = about("LR02", 10, "agent Router has no handler", "agent:Router");
        assertThat(review(settings("LR01.accept = project \"x\"\n"), a).findings())
                .containsExactly(a);
    }

    // ---- expiry (R42)

    @Test
    void anAcceptStopsHidingTheFindingAfterItsDateAndSaysSo() {
        Finding a = about("LR01", 10, "agent Router has no retry", "agent:Router");
        String line = "LR01.accept = on Router \"fix next sprint\" until 2026-10-01\n";
        ReviewReport r = review(settings(line), a);
        assertThat(r.findings()).containsExactly(a);
        assertThat(r.suppressed()).isEmpty();
        assertThat(r.notes()).anyMatch(n -> n.message().contains("expired on 2026-10-01"));
        // on the day itself, and before, it still holds
        ReviewReport still =
                review(ReviewSettings.parse("p", line).withToday(LocalDate.parse("2026-10-01")), a);
        assertThat(still.findings()).isEmpty();
        assertThat(still.suppressed().get(0).until()).isEqualTo("2026-10-01");
    }

    @Test
    void whoAndWhenAreKeptAndShown() {
        Finding a = about("LR01", 10, "agent Router has no retry", "agent:Router");
        ReviewReport r =
                review(
                        settings("LR01.accept = on Router \"ok\" by \"Srijith U\" at 2026-10-20\n"),
                        a);
        assertThat(r.suppressed().get(0).by()).isEqualTo("Srijith U");
        assertThat(r.suppressed().get(0).at()).isEqualTo("2026-10-20");
    }

    @Test
    void anAcceptWithoutAReasonHidesNothingAndIsReportedForEveryScope() {
        Finding a = about("LR01", 10, "agent Router has no retry", "agent:Router");
        ReviewReport r = review(settings("LR01.accept = project\n"), a);
        assertThat(r.findings()).extracting(Finding::rule).contains("LR01", "LV08");
    }

    @Test
    void aBadScopeOrDateIsASettingsErrorNamingTheLine() {
        assertThatThrownBy(() -> settings("LR01.accept = finding nothex \"x\"\n"))
                .isInstanceOf(InvalidSettingsException.class)
                .hasMessageContaining("review.properties:1");
        assertThatThrownBy(() -> settings("LR01.accept = project \"x\" until tomorrow\n"))
                .isInstanceOf(InvalidSettingsException.class);
        assertThatThrownBy(() -> settings("LR01.accept = project \"x\" until 2026-13-45\n"))
                .hasMessageContaining("is not a date");
    }

    // ---- writing accepts (R42, R44)

    @Test
    void theWriterMakesALineThatTheReaderTakesBack() {
        Finding a = about("LR01", 10, "agent Router has no retry", "agent:Router");
        String line =
                AcceptWriter.line(
                        a,
                        AcceptWriter.Scope.FINDING,
                        null,
                        "idempotent upstream",
                        LocalDate.parse("2026-12-31"),
                        "sri",
                        LocalDate.parse("2026-10-23"));
        ReviewReport r = review(settings(line + "\n"), a);
        assertThat(r.findings()).isEmpty();
        assertThat(r.suppressed().get(0).reason()).isEqualTo("idempotent upstream");
        assertThat(r.suppressed().get(0).by()).isEqualTo("sri");
    }

    @Test
    void theWriterRefusesAMissingReasonTheTrifectaAndAThingWithoutAName() {
        Finding a = about("LR01", 10, "x", "agent:Router");
        assertThatThrownBy(
                        () ->
                                AcceptWriter.line(
                                        a, AcceptWriter.Scope.FINDING, null, " ", null, "", null))
                .hasMessageContaining("needs a reason");
        Finding trifecta = f("LA01", Lens.SECURITY, Severity.HIGH, 3, "trifecta");
        assertThatThrownBy(
                        () ->
                                AcceptWriter.line(
                                        trifecta,
                                        AcceptWriter.Scope.FINDING,
                                        null,
                                        "ok",
                                        null,
                                        "",
                                        null))
                .hasMessageContaining("accepted in the script")
                .hasMessageContaining("trifecta: accepted");
        assertThatThrownBy(
                        () ->
                                AcceptWriter.line(
                                        a, AcceptWriter.Scope.THING, null, "ok", null, "", null))
                .hasMessageContaining("name of the thing");
        assertThatThrownBy(
                        () ->
                                AcceptWriter.line(
                                        a,
                                        AcceptWriter.Scope.PROJECT,
                                        null,
                                        "say \"no\"",
                                        null,
                                        "",
                                        null))
                .hasMessageContaining("double quote");
    }

    @Test
    void appendingKeepsTheFileAndSkipsALineThatIsAlreadyThere() throws Exception {
        Path file = dir.resolve("review.properties");
        Files.writeString(file, "# mine\nfail-on = high\n");
        assertThat(AcceptWriter.append(file, List.of("LR01.accept = project \"x\""))).isEqualTo(1);
        assertThat(AcceptWriter.append(file, List.of("LR01.accept = project \"x\""))).isZero();
        assertThat(Files.readString(file))
                .startsWith("# mine\nfail-on = high\n")
                .contains("LR01.accept = project \"x\"");
    }

    @Test
    void bulkAcceptOfSeriousFindingsIsFlagged() {
        assertThat(AcceptWriter.serious(f("LR01", Lens.RELIABILITY, Severity.HIGH, 1, "x")))
                .isTrue();
        assertThat(AcceptWriter.serious(f("LP01", Lens.PRIVACY, Severity.LOW, 1, "x"))).isTrue();
        assertThat(AcceptWriter.serious(f("LC05", Lens.COST, Severity.INFO, 1, "x"))).isFalse();
    }

    // ---- baseline (R46)

    @Test
    void aBaselineSetsKnownFindingsApartAndCountsOnlyNewOnes() {
        Finding known = about("LR01", 10, "agent Router has no retry", "agent:Router");
        Finding fresh = about("LR01", 20, "agent Writer has no retry", "agent:Writer");
        ReviewReport r =
                review(settings("").withBaseline(Set.of(known.fingerprint())), known, fresh);
        assertThat(r.findings()).containsExactly(fresh);
        assertThat(r.baselined()).containsExactly(known);
        assertThat(r.failsAt(Severity.INFO)).isTrue();
        assertThat(
                        review(settings("").withBaseline(Set.of(known.fingerprint())), known)
                                .failsAt(Severity.INFO))
                .isFalse();
    }

    @Test
    void theBaselineFileRoundTripsAndLeavesOutSeriousFindingsUnlessAsked() throws Exception {
        Path file = dir.resolve(Baseline.FILE_NAME);
        Finding mild = about("LR01", 10, "agent Router has no retry", "agent:Router");
        Finding high =
                f("LD02", Lens.DURABILITY, Severity.HIGH, 5, "tool Mailer repeats")
                        .withAnchor("tool:Mailer");
        Baseline.Written w =
                Baseline.write(
                        file,
                        List.of(mild, high),
                        false,
                        LocalDate.parse("2026-10-23"),
                        "adopting review");
        assertThat(w.recorded()).isEqualTo(1);
        assertThat(w.leftOut()).isEqualTo(1);
        assertThat(Baseline.read(file)).containsExactly(mild.fingerprint());
        assertThat(Files.readString(file))
                .contains("adopting review")
                .contains("2026-10-23")
                .contains("\"version\"");
        Baseline.write(file, List.of(mild, high), true, LocalDate.parse("2026-10-23"), "all");
        assertThat(Baseline.read(file))
                .containsExactlyInAnyOrder(mild.fingerprint(), high.fingerprint());
    }

    @Test
    void pruningRemovesOnlyEntriesWhoseFindingIsGone() throws Exception {
        Path file = dir.resolve(Baseline.FILE_NAME);
        Finding a = about("LR01", 10, "agent Router has no retry", "agent:Router");
        Finding b = about("LR01", 20, "agent Writer has no retry", "agent:Writer");
        Baseline.write(file, List.of(a, b), false, LocalDate.parse("2026-10-23"), "n");
        assertThat(Baseline.prune(file, Set.of(a.fingerprint()))).isEqualTo(1);
        assertThat(Baseline.read(file)).containsExactly(a.fingerprint());
        assertThat(Baseline.prune(dir.resolve("none.json"), Set.of())).isZero();
        assertThat(Baseline.read(dir.resolve("none.json"))).isEmpty();
    }

    @Test
    void aBrokenBaselineFileIsAClearError() throws Exception {
        Path file = dir.resolve(Baseline.FILE_NAME);
        Files.writeString(file, "{ not json");
        assertThatThrownBy(() -> Baseline.read(file))
                .hasMessageContaining("could not be read as a baseline");
    }

    // ---- noise (R47)

    @Test
    void theNoiseReportCountsPerRuleAndNamesTheOnesAcceptedAlmostEverywhere() {
        Finding a = about("LR01", 10, "agent A has no retry", "agent:A");
        Finding b = about("LR01", 20, "agent B has no retry", "agent:B");
        Finding c = about("LR01", 30, "agent C has no retry", "agent:C");
        Finding d = about("LC05", 40, "step X is big", "step:Main/delegate#1");
        ReviewReport r =
                review(
                        settings(
                                "LR01.accept = on A \"x\"\nLR01.accept = on B \"x\"\nLR01.accept = on C \"x\"\n"),
                        a,
                        b,
                        c,
                        d);
        String text = NoiseReport.write(r, 50);
        assertThat(text).contains("LR01").contains("accepted in 3 of 3 places").contains("LC05");
        assertThat(NoiseReport.write(review(settings(""), a), 50))
                .doesNotContain("Noisy in practice");
        assertThat(NoiseReport.write(review(settings("")), 50)).contains("no findings");
    }
}
