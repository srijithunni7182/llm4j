package io.github.llm4j.evalreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.evalreport.cli.Main;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real dashboard in a headless browser: every view, every drawer, no script error, no
 * network request, no markup injection. Skipped when Node with Playwright (or its Chromium) is not
 * available; set {@code PLAYWRIGHT_MODULE} to the module path if it is not resolvable.
 */
class BrowserSmokeTest {

    private static boolean has(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void everyViewRendersWithoutErrorsRequestsOrInjection(@TempDir Path tmp) throws Exception {
        String module = System.getenv().getOrDefault("PLAYWRIGHT_MODULE", "playwright");
        assumeTrue(
                has("node", "-e", "require('" + module.replace("'", "") + "')"),
                "Node with Playwright is not available");

        // hostile text in a scenario name and in an answer must stay inert
        Path store = tmp.resolve("store");
        copy(Path.of("docs/sample/bundles"), store);
        Path eval = store.resolve("runs/01JAFXV9H3FEATURE000147/evaluations.jsonl");
        Files.writeString(
                eval,
                Files.readString(eval)
                        .replace(
                                "We accept returns within 14 days of delivery.",
                                "<img src=x onerror=window.__pwned=1><script>window.__pwned=2</script>"));
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        assertThat(
                        Main.run(
                                new String[] {
                                    "render",
                                    store.toString(),
                                    "--run",
                                    "01JAFXV9H3FEATURE000147",
                                    "--out",
                                    tmp.resolve("out").toString()
                                },
                                new PrintStream(log),
                                new PrintStream(log)))
                .as(log.toString())
                .isZero();

        Process p =
                new ProcessBuilder(
                                "node",
                                "src/test/browser/smoke.js",
                                tmp.resolve("out").toAbsolutePath().toString())
                        .redirectErrorStream(true)
                        .start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        assertThat(out).contains("passed");
    }

    private static void copy(Path from, Path to) throws Exception {
        try (var s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path dst = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(p, dst);
                }
            }
        }
    }
}
