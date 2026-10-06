package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runs the tests of the shared graph renderer ({@code loom/graph-render}) with {@code node --test}, so a plain
 * {@code mvn test} covers it. Skipped when node is not installed.
 */
class GraphRenderJsTest {

    private static final Path DIR = Path.of("../graph-render");

    @Test
    void theRendererTestsPassUnderNode() throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        List<String> command = new ArrayList<>(List.of("node", "--test"));
        try (var files = Files.list(DIR.resolve("test"))) {
            files.map(Path::toString).filter(p -> p.endsWith(".test.js")).sorted().forEach(command::add);
        }

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as(output).isZero();
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
