package io.github.llm4j.evalreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.evalreport.cli.Main;
import io.github.llm4j.evalreport.config.ReportConfig;
import io.github.llm4j.evalreport.spi.ReportRunExportListener;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigAndCliTest {

    private static String run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        PrintStream p = new PrintStream(o);
        int code = Main.run(args, p, p);
        return code + "\n" + o;
    }

    @Test
    void yamlConfigSetsGoalsPrioritiesAndNoise(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("eval4j-report.yaml");
        Files.writeString(
                f,
                """
                defaultGoal: 80
                warnGap: 5
                project:
                  name: Acme
                compare:
                  noiseBand: 0.1
                  baseline: main
                dimensions:
                  correctness:
                    goal: 95
                    priority: CRITICAL
                  fancy-new:
                    name: Fancy
                    priority: none
                """);
        ReportConfig c = ReportConfig.load(f);
        assertThat(c.defaultGoal).isEqualTo(80);
        assertThat(c.noiseBand).isEqualTo(0.1);
        assertThat(c.projectName).isEqualTo("Acme");
        assertThat(c.dimension("correctness").goal()).isEqualTo(95);
        assertThat(c.dimension("correctness").priority().weight).isEqualTo(3);
        assertThat(c.dimension("fancy-new").priority().weight).isZero();
        assertThat(c.dimension("grounding").goal()).isEqualTo(80);
        assertThat(c.dimension("never-heard-of-it").name()).isEqualTo("Never heard of it");
        assertThat(ReportConfig.familyName("agents")).isEqualTo("Agents");
        assertThat(ReportConfig.facetName("rag")).isEqualTo("RAG");
        assertThat(ReportConfig.load(tmp.resolve("missing.yaml")).defaultGoal).isEqualTo(90);
    }

    @Test
    void invalidConfigurationIsAnErrorNamingTheKey(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("c.yaml");
        Files.writeString(f, "dimensions:\n  correctness:\n    goal: 140\n");
        assertThatThrownBy(() -> ReportConfig.load(f))
                .hasMessageContaining("dimensions.correctness.goal");
        Files.writeString(f, "dimensions:\n  correctness:\n    priority: URGENT\n");
        assertThatThrownBy(() -> ReportConfig.load(f)).hasMessageContaining("priority");
        Path j = tmp.resolve("c.json");
        Files.writeString(j, "{\"defaultGoal\": 70}");
        assertThat(ReportConfig.load(j).defaultGoal).isEqualTo(70);
    }

    @Test
    void cliListValidateCompareAndErrors(@TempDir Path tmp) throws Exception {
        RenderTest.store(tmp);
        assertThat(run("list", tmp.toString()))
                .startsWith("0\n")
                .contains("01JAGXW2K7CANDIDATE000148");
        assertThat(run("validate", tmp.toString())).startsWith("0\n").contains("0 warnings");
        assertThat(
                        run(
                                "compare",
                                tmp.toString(),
                                "--run",
                                "01JAGXW2K7CANDIDATE000148",
                                "--out",
                                tmp.resolve("o").toString()))
                .startsWith("0\n");
        assertThat(tmp.resolve("o/compare.md")).exists();
        assertThat(
                        run(
                                "render",
                                tmp.toString(),
                                "--no-compare",
                                "--out",
                                tmp.resolve("n").toString()))
                .startsWith("0\n");
        assertThat(tmp.resolve("n/compare.md")).doesNotExist();
        assertThat(run("bogus", tmp.toString())).startsWith("2\n");
        assertThat(run("render", tmp.toString(), "--run"))
                .startsWith("2\n")
                .contains("missing value");
        assertThat(run("render", tmp.toString(), "--run", "nope")).startsWith("3\n");
        assertThat(run("help")).startsWith("0\n").contains("usage");
    }

    @Test
    void listenerWritesTheDashboardAndNeverThrows(@TempDir Path tmp) throws Exception {
        RenderTest.store(tmp);
        new ReportRunExportListener().runFinished(tmp, tmp.resolve("runs/candidate"));
        assertThat(tmp.resolve("report/index.html")).exists();
        // a bad directory is reported, not thrown
        new ReportRunExportListener()
                .runFinished(tmp.resolve("nothing"), tmp.resolve("nothing/runs/x"));
    }
}
