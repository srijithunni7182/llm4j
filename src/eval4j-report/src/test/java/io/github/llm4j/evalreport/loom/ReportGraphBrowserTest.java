package io.github.llm4j.evalreport.loom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.evalreport.cli.Main;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * V10.1 to V10.16: the generated report, opened in a real browser. The browser checks live in
 * {@code src/test/js/report-graph.browser.js}; this test builds the report and runs them. It is skipped when node
 * or a browser is not installed.
 */
class ReportGraphBrowserTest {

    private static Path report(Path tmp) throws Exception {
        Path root = store(tmp);
        Files.writeString(root.resolve("runs/candidate/traces.jsonl"), ReportGraphFixtures.traces(), StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"render", root.toString()}, new PrintStream(out), new PrintStream(out));
        assertThat(code).as(out.toString()).isZero();
        Path html = root.resolve("report/index.html");
        String keep = System.getProperty("graph.report.out");
        if (keep != null) {
            Files.copy(html, Path.of(keep), StandardCopyOption.REPLACE_EXISTING);
        }
        return html;
    }

    /** A run store holding the minimal example's two runs. */
    private static Path store(Path tmp) throws Exception {
        Path src = Path.of("src/test/resources/examples/minimal");
        for (String run : new String[] {"previous", "candidate"}) {
            Path dst = tmp.resolve("runs").resolve(run);
            Files.createDirectories(dst);
            try (var files = Files.list(src.resolve(run))) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Files.copy(f, dst.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return tmp;
    }

    @Test
    void theReportIsOneFileWithTheRendererInlineAndNothingFetched(@TempDir Path tmp) throws Exception {
        String html = Files.readString(report(tmp));

        assertThat(html).contains("root.LoomGraph").contains("EvalGraphCard");
        assertThat(html).doesNotContainPattern(Pattern.compile("(src|href)=\"(https?:)?//"));
        assertThat(html).doesNotContainPattern(Pattern.compile("<script[^>]*\\bsrc="));
        assertThat(html).doesNotContainPattern(Pattern.compile("<link[^>]*rel=\"stylesheet\""));
        assertThat(html).doesNotContain("fetch(").doesNotContain("XMLHttpRequest").doesNotContain("@import");
        assertThat(html).doesNotContain("loom_logo");
        // the inlined scripts cannot close their own element
        for (String name : new String[] {"graph-render.js", "graph-card.js"}) {
            String js = Files.readString(Path.of("src/main/resources/io/github/llm4j/evalreport/render/" + name));
            assertThat(js).as(name).doesNotContainIgnoringCase("</script");
        }
    }

    @Test
    void theGraphCardWorksInABrowserInLightAndDark(@TempDir Path tmp) throws Exception {
        Path html = report(tmp);
        assumeTrue(commandExists("node"), "node is not installed");

        Process process = new ProcessBuilder("node", "src/test/js/report-graph.browser.js", html.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();

        assumeTrue(code != 77, "no browser available: " + output);
        assertThat(code).as(output).isZero();
        assertThat(output).contains("checks passed").doesNotContain("FAIL");
    }

    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
