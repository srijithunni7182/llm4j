package io.github.llm4j.evalreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.evalreport.config.ReportConfig;
import io.github.llm4j.evalreport.format.RunStore;
import io.github.llm4j.evalreport.model.ReportModel;
import io.github.llm4j.evalreport.model.ReportModel.CompareModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The documented example bundles must reproduce spec/examples/minimal/expected.json (RPT-50). */
class ExampleBundleTest {

    static Path root;
    static JsonNode expected;

    @BeforeAll
    static void setUp(@TempDir Path tmp) throws IOException {
        root = tmp;
        Path src = Path.of("src/test/resources/examples/minimal");
        for (String run : new String[] {"previous", "candidate"}) {
            Path dst = tmp.resolve("runs").resolve(run);
            Files.createDirectories(dst);
            try (var files = Files.list(src.resolve(run))) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Files.copy(
                            f, dst.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        expected = new ObjectMapper().readTree(src.resolve("expected.json").toFile());
    }

    private ReportModel build() {
        // the runs are stored under their directory names; look the ids up from run.json
        RunStore store = new RunStore(root);
        return EvalReport.build(
                store, "01JAGXW2K7CANDIDATE000148", null, true, ReportConfig.defaults());
    }

    @Test
    void overallCountsMatch() {
        ReportModel m = build();
        JsonNode o = expected.path("candidate").path("overall");
        assertThat(m.overall().passed()).isEqualTo(o.path("passed").asInt());
        assertThat(m.overall().failed()).isEqualTo(o.path("failed").asInt());
        assertThat(m.overall().notEvaluated()).isEqualTo(o.path("notEvaluated").asInt());
        assertThat(m.overall().rate()).isCloseTo(o.path("rate").asDouble(), within(0.001));
    }

    @Test
    void dimensionsAndFamiliesMatch() {
        ReportModel m = build();
        expected.path("candidate")
                .path("dimensions")
                .fields()
                .forEachRemaining(
                        en -> {
                            var d =
                                    m.dimensions().stream()
                                            .filter(x -> x.id().equals(en.getKey()))
                                            .findFirst()
                                            .orElseThrow();
                            assertThat(d.rollup().rate())
                                    .as(en.getKey())
                                    .isCloseTo(
                                            en.getValue().path("rate").asDouble(), within(0.001));
                            assertThat(d.rollup().passed() + d.rollup().failed())
                                    .isEqualTo(en.getValue().path("counted").asInt());
                        });
        expected.path("candidate")
                .path("families")
                .fields()
                .forEachRemaining(
                        en -> {
                            var f =
                                    m.families().stream()
                                            .filter(x -> x.id().equals(en.getKey()))
                                            .findFirst()
                                            .orElseThrow();
                            assertThat(f.rollup().rate())
                                    .as(en.getKey())
                                    .isCloseTo(
                                            en.getValue().path("rate").asDouble(), within(0.001));
                        });
    }

    @Test
    void coverageStatesMatch() {
        ReportModel m = build();
        expected.path("candidate")
                .path("coverage")
                .fields()
                .forEachRemaining(
                        en -> {
                            var d =
                                    m.dimensions().stream()
                                            .filter(x -> x.id().equals(en.getKey()))
                                            .findFirst()
                                            .orElseThrow();
                            assertThat(d.coverage().state())
                                    .as(en.getKey())
                                    .isEqualTo(en.getValue().path("state").asText());
                            assertThat(d.coverage().declared())
                                    .as(en.getKey())
                                    .isEqualTo(en.getValue().path("declared").asInt());
                        });
        // declared but unevaluated dimensions are present and excluded from the weighted rate
        var compliance =
                m.dimensions().stream()
                        .filter(x -> x.id().equals("compliance"))
                        .findFirst()
                        .orElseThrow();
        assertThat(compliance.rollup().rate()).isNull();
        assertThat(compliance.status()).isEqualTo("NO_RESULTS");
    }

    @Test
    void comparisonMatches() {
        CompareModel c = build().compare();
        JsonNode e = expected.path("compare");
        assertThat(c).isNotNull();
        assertThat(c.baselineRun()).isEqualTo(e.path("baselineRun").asText());
        assertThat(c.matched()).isEqualTo(e.path("matched").asInt());
        assertThat(c.worse()).isEqualTo(e.path("worse").asInt());
        assertThat(c.better()).isEqualTo(e.path("better").asInt());
        assertThat(c.same()).isEqualTo(e.path("same").asInt());
        assertThat(c.withinNoise()).isEqualTo(e.path("withinNoise").asInt());
        assertThat(c.added()).isEqualTo(e.path("new").asInt());
        assertThat(c.removed()).isEqualTo(e.path("removed").asInt());
        assertThat(c.notComparable()).isEqualTo(e.path("notComparable").asInt());
        Set<String> worseKeys = new HashSet<>();
        c.changed().stream()
                .filter(x -> x.change().equals("WORSE"))
                .forEach(x -> worseKeys.add(x.key()));
        Set<String> expectedKeys = new HashSet<>();
        e.path("worseKeys").forEach(k -> expectedKeys.add(k.asText()));
        assertThat(worseKeys).isEqualTo(expectedKeys);
        e.path("byDimensionMatched")
                .fields()
                .forEachRemaining(
                        en -> {
                            var d =
                                    c.dimensions().stream()
                                            .filter(x -> x.id().equals(en.getKey()))
                                            .findFirst()
                                            .orElseThrow();
                            assertThat(d.baseRate())
                                    .as(en.getKey())
                                    .isCloseTo(
                                            en.getValue().path("baseRate").asDouble(),
                                            within(0.001));
                            assertThat(d.candRate())
                                    .as(en.getKey())
                                    .isCloseTo(
                                            en.getValue().path("candRate").asDouble(),
                                            within(0.001));
                            assertThat(d.worse()).isEqualTo(en.getValue().path("worse").asInt());
                        });
    }

    @Test
    void duplicatingARunAsBaselineChangesNothing() {
        RunStore store = new RunStore(root);
        var bundle = store.load("01JAGXW2K7CANDIDATE000148", false);
        var cm =
                io.github.llm4j.evalreport.analysis.Compare.compare(
                        bundle, bundle, ReportConfig.defaults(), "self");
        assertThat(cm.worse() + cm.better() + cm.added() + cm.removed()).isZero();
    }
}
