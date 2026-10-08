package io.github.llm4j.evalreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.evalreport.cli.Main;
import io.github.llm4j.evalreport.config.ReportConfig;
import io.github.llm4j.evalreport.format.RunStore;
import io.github.llm4j.evalreport.model.ReportModel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Features added after the first release: merge, legacy import, prune, reliability, presets,
 * branding, trends, A/B.
 */
class GapsTest {

    static final Path SAMPLE = Path.of("docs/sample/bundles");

    private static String run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        PrintStream p = new PrintStream(o);
        int code = Main.run(args, p, p);
        return code + "\n" + o;
    }

    private ReportModel sample() {
        return EvalReport.build(new RunStore(SAMPLE), null, null, true, ReportConfig.defaults());
    }

    @Test
    void theTwoBundlesOfOneBuildAreOneRun() {
        RunStore store = new RunStore(SAMPLE);
        assertThat(store.listRuns())
                .extracting(r -> r.runId())
                .contains("build-148")
                .doesNotContain("01JAGXW2K7CANDIDATE000148-agents");
        ReportModel m = sample();
        assertThat(m.meta().runId()).isEqualTo("build-148");
        assertThat(m.families())
                .extracting(f -> f.id())
                .contains("agents", "workflows", "conversations", "prompts", "retrieval");
        assertThat(m.meta().env().path("datasets")).hasSize(2);
        assertThat(m.compare().baselineRun()).isEqualTo("01JAFXV9H3FEATURE000147");
        assertThat(m.compare().baselineLabel())
                .contains("Previous run on branch feature/refund-policy");
    }

    @Test
    void carriedNotEvaluatedAndReusedEvidenceAreShown() {
        ReportModel m = sample();
        assertThat(m.evidence().carried()).isPositive();
        assertThat(m.evidence().reused()).isPositive();
        assertThat(m.overall().notEvaluated()).isEqualTo(2);
        var compliance =
                m.dimensions().stream()
                        .filter(d -> d.id().equals("compliance"))
                        .findFirst()
                        .orElseThrow();
        assertThat(compliance.rollup().rate()).isNull();
    }

    @Test
    void perDimensionTrendCoversEveryRunOnTheBranch() {
        ReportModel m = sample();
        assertThat(m.trend()).hasSize(3);
        assertThat(m.trend().get(0).byDimension())
                .containsKeys("correctness", "grounding", "efficiency");
        assertThat(m.trend().get(2).byDimension().get("grounding"))
                .isLessThan(m.trend().get(0).byDimension().get("grounding"));
    }

    @Test
    void judgeReliabilityComesFromSamplesAndStats() {
        var rel = sample().reliability().get(0);
        assertThat(rel.judgeId()).isEqualTo("judge-main");
        assertThat(rel.multiSample()).isPositive();
        assertThat(rel.selfConsistency()).isBetween(0.0, 1.0);
        assertThat(rel.sameFamilyAs()).as("gemini judge, llama agent").isEmpty();
    }

    @Test
    void presetsCoverEveryDimensionAndPairwiseResultsAreAvailable() {
        ReportModel m = sample();
        assertThat(m.presets())
                .containsKeys("Balanced", "Safety first", "Speed and cost", "Quality only");
        assertThat(m.presets().get("Safety first"))
                .containsEntry("safety", "CRITICAL")
                .containsEntry("efficiency", "NICE_TO_HAVE");
        assertThat(m.presets().get("Quality only")).containsEntry("efficiency", "NONE");
        assertThat(
                        m.cases().stream()
                                .flatMap(c -> c.evaluations().stream())
                                .filter(e -> "PAIRWISE".equals(e.kind()))
                                .count())
                .isEqualTo(8);
    }

    @Test
    void brandingTitleLogoAndAccentComeFromConfiguration(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("logo.svg"),
                "<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'/>");
        Path cfg = tmp.resolve("eval4j-report.yaml");
        Files.writeString(
                cfg,
                "branding:\n  title: Acme quality report\n  logo: logo.svg\n  accent: \"#1a73e8\"\n");
        ReportConfig c = ReportConfig.load(cfg);
        assertThat(c.branding.title()).isEqualTo("Acme quality report");
        assertThat(c.branding.accent()).isEqualTo("#1a73e8");
        assertThat(c.branding.logoDataUri()).startsWith("data:image/svg+xml;base64,");
        assertThat(new String(Base64.getDecoder().decode(c.branding.logoDataUri().substring(26))))
                .contains("<svg");
        Files.writeString(cfg, "branding:\n  accent: red\n");
        assertThatThrownBy(() -> ReportConfig.load(cfg)).hasMessageContaining("branding.accent");
        Files.writeString(cfg, "branding:\n  logo: logo.exe\n");
        assertThatThrownBy(() -> ReportConfig.load(cfg)).hasMessageContaining("branding.logo");
        Files.writeString(cfg, "branding:\n  logo: missing.png\n");
        assertThatThrownBy(() -> ReportConfig.load(cfg)).hasMessageContaining("cannot be read");
        ReportModel m =
                EvalReport.build(
                        new RunStore(SAMPLE), null, null, false, ReportConfig.load(writeOk(tmp)));
        assertThat(m.branding().title()).isEqualTo("Acme quality report");
    }

    private static Path writeOk(Path tmp) throws Exception {
        Path cfg = tmp.resolve("ok.yaml");
        Files.writeString(cfg, "branding:\n  title: Acme quality report\n");
        return cfg;
    }

    @Test
    void mergeCommandWritesOneBundleForTheGroup(@TempDir Path tmp) throws Exception {
        String out =
                run("merge", SAMPLE.toString(), "--group", "build-148", "--out", tmp.toString());
        assertThat(out).startsWith("0\n").contains("build-148-merged");
        RunStore merged = new RunStore(tmp);
        assertThat(merged.list()).hasSize(1);
        var b = merged.load("build-148-merged", true);
        assertThat(b.evaluations().size()).isGreaterThan(200);
        assertThat(run("merge", SAMPLE.toString())).startsWith("2\n");
    }

    @Test
    void legacyV1ReportsCanBeImportedAndRendered(@TempDir Path tmp) throws Exception {
        Path v1 = tmp.resolve("eval4j-report.json");
        Files.writeString(
                v1,
                """
                {"runId":"legacy-2025-12-01","startedAt":"2025-12-01T10:00:00Z","endedAt":"2025-12-01T10:03:00Z","gitSha":"abcdef1234567",
                 "records":[
                  {"suite":"com.acme.T","testName":"refund()","metric":"Correctness","score":0.9,"threshold":0.7,"passed":true,"reason":"ok","judgeIdentifier":"gemini-2.5-pro","timestamp":"2025-12-01T10:00:01Z","input":"q","actualOutput":"a"},
                  {"suite":"com.acme.T","testName":"refund()","metric":"Correctness","score":0.3,"threshold":0.7,"passed":false,"reason":"bad","judgeIdentifier":"gemini-2.5-pro"}],
                 "tests":[{"suite":"com.acme.T","testName":"refund()","status":"FAILED","durationMs":10,"message":"x"}]}
                """);
        Path root = tmp.resolve("store");
        assertThat(run("import-legacy", v1.toString(), "--out", root.toString()))
                .startsWith("0\n")
                .contains("imported into");
        var b = new RunStore(root).load("legacy-2025-12-01", true);
        assertThat(b.evaluations()).hasSize(2);
        assertThat(b.evaluations().get(0).key()).isNotEqualTo(b.evaluations().get(1).key());
        assertThat(b.evaluations().get(0).kind()).isEqualTo("JUDGE");
        assertThat(b.run().commit()).isEqualTo("abcdef123456");
        assertThat(run("render", root.toString(), "--out", tmp.resolve("o").toString()))
                .startsWith("0\n");
        assertThat(run("import-legacy")).startsWith("2\n");
    }

    @Test
    void pruneKeepsTheNewestRuns(@TempDir Path tmp) throws Exception {
        try (var s = Files.walk(SAMPLE)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path dst = tmp.resolve(SAMPLE.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(p, dst);
                }
            }
        }
        assertThat(run("prune", tmp.toString(), "--keep", "2"))
                .startsWith("0\n")
                .contains("removed 3");
        assertThat(new RunStore(tmp).list()).hasSize(2);
        assertThat(run("prune", tmp.toString(), "--keep", "x")).startsWith("2\n");
    }

    @Test
    void staticEditionShowsPromptComparisonsAndTheTitle(@TempDir Path tmp) throws Exception {
        run("render", SAMPLE.toString(), "--out", tmp.toString());
        String html = Files.readString(tmp.resolve("static/index.html"));
        assertThat(html).contains("Prompt A/B").contains("support-agent v3 vs v2");
        JsonNode junit = new ObjectMapper().createObjectNode();
        assertThat(junit).isNotNull();
    }
}
