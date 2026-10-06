package io.github.llm4j.evalreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runs the plain-JavaScript tests of the report's graph card with {@code node --test}, and checks that the report's
 * copy of the shared graph renderer is the canonical file. The JavaScript tests are skipped when node is not installed.
 */
class JsUnitTests {

    private static final Path RENDER = Path.of("src/main/resources/io/github/llm4j/evalreport/render");
    private static final Path CANONICAL = Path.of("../loom/graph-render/graph-render.js");

    @Test
    void theGraphCardTestsPassUnderNode() throws Exception {
        assumeTrue(nodeVersion() != null, "node is not installed");
        List<String> files;
        try (var s = Files.list(Path.of("src/test/js"))) {
            files = s.map(Path::toString).filter(p -> p.endsWith(".test.js")).sorted().toList();
        }
        List<String> command = new java.util.ArrayList<>(List.of("node", "--test"));
        command.addAll(files);

        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(p.waitFor()).as(output).isZero();
    }

    @Test
    void theReportsCopyOfTheRendererIsTheCanonicalFile() throws Exception {
        assertThat(CANONICAL).exists();
        assertThat(sha(RENDER.resolve("graph-render.js")))
                .as("run scripts/sync-graph-render.sh")
                .isEqualTo(sha(CANONICAL));
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String nodeVersion() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            String v = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }
}
