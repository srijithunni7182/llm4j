package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** V4.3: Loom does not depend on eval4j or its report, so the report can depend on Loom and the graph is built once. */
class ModuleBoundaryTest {

    @Test
    void thePomDeclaresNoEvalDependency() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).doesNotContain("<artifactId>eval4j").doesNotContain("eval4j-report");
    }

    @Test
    void noMainClassImportsEval4j() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            String offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains("io.github.llm4j.eval") || read(p).contains("io.github.llm4j.evalreport"))
                    .map(Path::toString)
                    .collect(Collectors.joining(", "));

            assertThat(offenders).isEmpty();
        }
    }

    @Test
    void theGraphPackageUsesOnlyTheParserTheAstAndJackson() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/io/github/llm4j/loom/graph"))) {
            String imports = files.filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> read(p).lines())
                    .filter(l -> l.startsWith("import "))
                    .collect(Collectors.joining("\n"));

            assertThat(imports).doesNotContain("execution.").doesNotContain("runtime.").doesNotContain("tools.")
                    .doesNotContain("picocli").doesNotContain("io.github.llm4j.agent");
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
