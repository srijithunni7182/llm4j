package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Fix;
import io.github.llm4j.loom.review.model.FixSafety;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.model.TextEdit;
import io.github.llm4j.loom.review.report.SarifReport;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R8 of spec 09: SARIF 2.1.0 for code scanning. */
class SarifTest {

    static Finding finding(String rule, Severity sev, Location where, Optional<Fix> fix) {
        return new Finding(
                rule,
                Lens.RELIABILITY,
                sev,
                where,
                "title of " + rule,
                "why",
                "do this",
                List.of("LLM06"),
                fix,
                List.of(),
                "pack core@1.0.0");
    }

    JsonNode sarif(String properties, Finding... findings) throws Exception {
        ReviewReport r =
                new ReviewEngine(List.of(ReviewEngineTest.source("s", findings)))
                        .review(
                                ReviewEngineTest.INPUT,
                                ReviewSettings.parse("review.properties", properties));
        return new ObjectMapper().readTree(SarifReport.write(r, Reviews.catalog()));
    }

    @Test
    void theDocumentHasTheRequiredSarifShape() throws Exception {
        JsonNode s =
                sarif(
                        "",
                        finding(
                                "LR01",
                                Severity.MEDIUM,
                                Location.at("main.loom", 4),
                                Optional.empty()));
        assertThat(s.get("version").asText()).isEqualTo("2.1.0");
        assertThat(s.get("$schema").asText()).contains("sarif-schema-2.1.0");
        JsonNode run = s.get("runs").get(0);
        assertThat(run.at("/tool/driver/name").asText()).isEqualTo("weave review");
        JsonNode result = run.get("results").get(0);
        assertThat(result.get("ruleId").asText()).isEqualTo("LR01");
        assertThat(result.at("/message/text").asText()).contains("title of LR01");
        assertThat(result.at("/locations/0/physicalLocation/artifactLocation/uri").asText())
                .isEqualTo("main.loom");
        assertThat(result.at("/locations/0/physicalLocation/region/startLine").asInt())
                .isEqualTo(4);
        assertThat(result.at("/properties/fixHint").asText()).isEqualTo("do this");
        assertThat(result.at("/properties/owasp/0").asText()).isEqualTo("LLM06");
        assertThat(result.at("/properties/source").asText()).isEqualTo("pack core@1.0.0");
    }

    @Test
    void severityMapsToSarifLevel() throws Exception {
        JsonNode results =
                sarif(
                                "",
                                finding(
                                        "LR01",
                                        Severity.HIGH,
                                        Location.at("m", 1),
                                        Optional.empty()),
                                finding(
                                        "LR02",
                                        Severity.MEDIUM,
                                        Location.at("m", 2),
                                        Optional.empty()),
                                finding(
                                        "LR03",
                                        Severity.LOW,
                                        Location.at("m", 3),
                                        Optional.empty()),
                                finding(
                                        "LR04",
                                        Severity.INFO,
                                        Location.at("m", 4),
                                        Optional.empty()))
                        .at("/runs/0/results");
        assertThat(results)
                .extracting(n -> n.get("level").asText())
                .containsExactly("error", "warning", "note", "note");
    }

    @Test
    void aWholeScriptFindingHasNoRegion() throws Exception {
        JsonNode r =
                sarif(
                                "",
                                finding(
                                        "LR01",
                                        Severity.LOW,
                                        Location.at("main.loom", 0),
                                        Optional.empty()))
                        .at("/runs/0/results/0/locations/0/physicalLocation");
        assertThat(r.has("region")).isFalse();
    }

    @Test
    void theDriverListsEachRuleOnceWithItsDescriptionFromTheCatalog() throws Exception {
        JsonNode rules =
                sarif(
                                "",
                                finding(
                                        "LR01",
                                        Severity.LOW,
                                        Location.at("m", 1),
                                        Optional.empty()),
                                finding(
                                        "LR01",
                                        Severity.LOW,
                                        Location.at("m", 2),
                                        Optional.empty()))
                        .at("/runs/0/tool/driver/rules");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).get("id").asText()).isEqualTo("LR01");
        assertThat(rules.get(0).at("/shortDescription/text").asText()).isNotBlank();
        assertThat(rules.get(0).at("/properties/source").asText()).startsWith("pack core@");
    }

    @Test
    void aFixBecomesASarifFixWithItsReplacements() throws Exception {
        Fix fix =
                new Fix(
                        FixSafety.SAFE,
                        "add a timeout",
                        List.of(new TextEdit("main.loom", 4, 10, 4, 10, " timeout 60s")));
        JsonNode f =
                sarif(
                                "",
                                finding(
                                        "LR01",
                                        Severity.LOW,
                                        Location.at("main.loom", 4),
                                        Optional.of(fix)))
                        .at("/runs/0/results/0/fixes/0");
        assertThat(f.at("/description/text").asText()).isEqualTo("add a timeout");
        JsonNode change = f.at("/artifactChanges/0");
        assertThat(change.at("/artifactLocation/uri").asText()).isEqualTo("main.loom");
        assertThat(change.at("/replacements/0/deletedRegion/startLine").asInt()).isEqualTo(4);
        assertThat(change.at("/replacements/0/insertedContent/text").asText())
                .isEqualTo(" timeout 60s");
    }

    @Test
    void aSuppressedFindingIsListedWithItsReasonAsASuppression() throws Exception {
        JsonNode results =
                sarif(
                                "LR01.suppress = line 4 \"checked by hand\"\n",
                                finding(
                                        "LR01",
                                        Severity.MEDIUM,
                                        Location.at("m", 4),
                                        Optional.empty()))
                        .at("/runs/0/results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).at("/suppressions/0/justification").asText())
                .isEqualTo("checked by hand");
        assertThat(results.get(0).at("/suppressions/0/kind").asText()).isEqualTo("external");
    }

    @Test
    void anEmptyReviewIsAValidDocumentWithNoResults() throws Exception {
        JsonNode s = sarif("");
        assertThat(s.at("/runs/0/results")).isEmpty();
        assertThat(s.at("/runs/0/tool/driver/rules")).isEmpty();
    }
}
