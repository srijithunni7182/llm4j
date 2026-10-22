package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.pack.DataRuleSource;
import io.github.llm4j.loom.review.pack.Pack;
import io.github.llm4j.loom.review.pack.PackLoader;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R38, R39, R71 of spec 09: data rules run over the facts; undeclared needs are reported, never
 * silently passed.
 */
class DataRuleSourceTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            agent A { model: "gemini-flash-latest"  system: "s" }
            workflow Main(q) { delegate "{q}" to A -> r  note "{r}" }
            """;

    static final String EXAMPLES =
            "examples: { fires: [{ inline: \"agent A { model: \\\"m\\\"  system: \\\"s\\\" }\\nworkflow Main(q) { delegate \\\"{q}\\\" to A -> r  note \\\"{r}\\\" }\" }], quiet: [{ inline: \"agent A { model: \\\"m\\\"  system: \\\"s\\\" }\\nworkflow Main(q) { note \\\"hi\\\" }\" }] }\n";

    Pack pack(String... ruleDocs) throws IOException {
        Path p = dir.resolve("pack");
        Files.createDirectories(p.resolve("rules"));
        Files.writeString(
                p.resolve("pack.yaml"),
                "name: acme\nversion: 1.2.3\nfactModel: 1\nrulesSchema: 1\nprefix: ACME\n");
        for (int i = 0; i < ruleDocs.length; i++)
            Files.writeString(p.resolve("rules/r" + i + ".yaml"), ruleDocs[i]);
        return PackLoader.directory(p);
    }

    static String rule(String id, String lens, String about, String when, String extra) {
        return "id: "
                + id
                + "\nlens: "
                + lens
                + "\nseverity: medium\nabout: "
                + about
                + "\nwhen: "
                + when
                + "\ntitle: \""
                + (about.equals("step") ? "{label}" : "{name}")
                + " here\"\nwhy: \"because\"\nfixHint: \"do it\"\n"
                + EXAMPLES
                + extra;
    }

    static class Sink implements FindingSource.Sink {
        final List<Finding> findings = new ArrayList<>();
        final List<Note> notes = new ArrayList<>();

        public void finding(Finding f) {
            findings.add(f);
        }

        public void note(Note n) {
            notes.add(n);
        }
    }

    ReviewInput input(boolean graph) throws IOException {
        Files.createDirectories(dir.resolve("script"));
        return ReviewFixtures.input(dir.resolve("script"), SCRIPT, Map.of(), graph);
    }

    Sink collect(Pack pack, ReviewSettings settings, boolean graph) throws IOException {
        Sink sink = new Sink();
        new DataRuleSource(List.of(pack)).collect(input(graph), settings, sink);
        return sink;
    }

    @Test
    void aFiringRuleReportsOneFindingPerEntityWithItsLocationTitleAndProvenance() throws Exception {
        Pack p = pack(rule("ACME-001", "reliability", "agent", "{ field: name, is: A }", ""));
        Sink s = collect(p, ReviewSettings.defaults(), true);
        assertThat(s.findings).hasSize(1);
        Finding f = s.findings.get(0);
        assertThat(f.rule()).isEqualTo("ACME-001");
        assertThat(f.title()).isEqualTo("A here");
        assertThat(f.why()).isEqualTo("because");
        assertThat(f.fixHint()).isEqualTo("do it");
        assertThat(f.source()).isEqualTo("pack acme@1.2.3");
        assertThat(f.where().line()).isGreaterThan(0);
        assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void stepFindingsAreTiedToTheirGraphNode() throws Exception {
        Pack p = pack(rule("ACME-002", "reliability", "step", "{ field: kind, is: delegate }", ""));
        Finding f = collect(p, ReviewSettings.defaults(), true).findings.get(0);
        assertThat(f.where().nodeId()).matches("n\\d+");
    }

    @Test
    void aLensThatIsOffAndARuleTurnedOffAreNotRun() throws Exception {
        Pack p =
                pack(
                        rule("ACME-003", "governance", "agent", "{ field: name, is: A }", ""),
                        rule("ACME-004", "reliability", "agent", "{ field: name, is: A }", ""));
        assertThat(collect(p, ReviewSettings.defaults(), true).findings)
                .extracting(Finding::rule)
                .containsExactly("ACME-004");
        assertThat(collect(p, ReviewSettings.defaults().withLenses("+governance"), true).findings)
                .hasSize(2);
        assertThat(collect(p, ReviewSettings.parse("p", "ACME-004 = off\n"), true).findings)
                .isEmpty();
    }

    @Test
    void aHeuristicRuleRunsOnlyWhenHeuristicsAreOnOrTheRuleIsNamed() throws Exception {
        Pack p =
                pack(
                        rule(
                                "ACME-005",
                                "reliability",
                                "agent",
                                "{ field: name, is: A }",
                                "heuristic: true\n"));
        assertThat(collect(p, ReviewSettings.defaults(), true).findings).isEmpty();
        assertThat(collect(p, ReviewSettings.defaults().withHeuristics(true), true).findings)
                .hasSize(1);
        assertThat(collect(p, ReviewSettings.parse("p", "ACME-005 = on\n"), true).findings)
                .hasSize(1);
    }

    @Test
    void aRuleThatNeedsAnUndeclaredSettingIsReportedAsNotEvaluatedNeverAsAPass() throws Exception {
        Pack p =
                pack(
                        rule(
                                "ACME-006",
                                "reliability",
                                "agent",
                                "{ field: model.provider, notIn: { setting: data.regions } }",
                                "requires: [setting.data.regions]\n"));
        Sink undeclared = collect(p, ReviewSettings.defaults(), true);
        assertThat(undeclared.findings).isEmpty();
        assertThat(undeclared.notes).hasSize(1);
        assertThat(undeclared.notes.get(0).kind()).isEqualTo(Note.Kind.NOT_EVALUATED);
        assertThat(undeclared.notes.get(0).message())
                .contains("ACME-006")
                .contains("data.regions is not declared");
        Sink declared = collect(p, ReviewSettings.parse("p", "data.regions = eu\n"), true);
        assertThat(declared.notes).isEmpty();
        assertThat(declared.findings).hasSize(1);
    }

    @Test
    void aStepRuleWithoutAGraphIsNotEvaluatedAndSaysSo() throws Exception {
        Pack p = pack(rule("ACME-007", "reliability", "step", "{ field: kind, is: delegate }", ""));
        Sink s = collect(p, ReviewSettings.defaults(), false);
        assertThat(s.findings).isEmpty();
        assertThat(s.notes).hasSize(1);
        assertThat(s.notes.get(0).message()).contains("workflow graph could not be built");
    }

    @Test
    void aConditionOnAnUnknownFactDoesNotFireEvenWhenNegated() throws Exception {
        Pack p =
                pack(
                        rule(
                                "ACME-008",
                                "reliability",
                                "agent",
                                "{ not: { field: setting.data.personal, is: x } }",
                                ""));
        assertThat(collect(p, ReviewSettings.defaults(), true).findings).isEmpty();
    }

    @Test
    void theEngineAppliesSettingsToDataFindingsLikeAnyOther() throws Exception {
        Pack p = pack(rule("ACME-009", "reliability", "agent", "{ field: name, is: A }", ""));
        ReviewEngine engine = new ReviewEngine(List.of(new DataRuleSource(List.of(p))));
        ReviewReport r =
                engine.review(input(true), ReviewSettings.parse("p", "ACME-009.severity = high\n"));
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).severity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void severalPacksShareOneSetOfFacts() throws Exception {
        Pack a = pack(rule("ACME-010", "reliability", "agent", "{ field: name, is: A }", ""));
        Sink s = new Sink();
        new DataRuleSource(List.of(a, a)).collect(input(true), ReviewSettings.defaults(), s);
        assertThat(s.findings).hasSize(2);
    }
}
