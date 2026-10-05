package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * V3.8: the JSON and Mermaid for the sample scripts match committed files exactly. Regenerate them after an
 * intended change with {@code -Dgolden.update=true} and review the diff.
 */
class GraphGoldenTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path GOLDEN = Path.of("src/test/resources/graph/golden");

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "content_factory, samples/content_factory/main.loom",
        "boardroom, samples/boardroom/main.loom",
        "digest, samples/digest/digest.loom",
        "imports_parent, src/test/resources/imports/parent.loom"
    })
    void jsonAndMermaidMatchTheCommittedFiles(String name, String script) throws IOException {
        GraphResult result = new GraphService().graph(Path.of(script));

        check(GOLDEN.resolve(name + ".json"), portable(GraphJson.write(result)) + "\n");
        String mermaid = result.workflows().stream()
                .map(w -> "%% workflow " + w.name() + "\n" + MermaidRenderer.render(w))
                .collect(Collectors.joining("\n"));
        check(GOLDEN.resolve(name + ".mmd"), portable(mermaid));
    }

    /** Machine-specific paths are replaced so the committed files do not depend on where the repo is checked out. */
    private static String portable(String text) {
        return text.replace(ROOT.toString(), "<root>");
    }

    private static void check(Path file, String actual) throws IOException {
        if (Boolean.getBoolean("golden.update") || !Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, actual);
        }
        assertThat(actual).as(file.toString()).isEqualTo(Files.readString(file));
    }
}
