package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The optimizer changes things; the rest of eval4j only measures. The dependency must run one way:
 * {@code optimize} may use everything else, and nothing else may reference {@code optimize}.
 */
class OptimizerArchitectureTest {

    private static final Path CLASSES = Path.of("target/classes");
    private static final String OPTIMIZE = "io/github/llm4j/eval/optimize/";

    private static List<Path> mainClassFiles() throws IOException {
        try (Stream<Path> files = Files.walk(CLASSES)) {
            return files.filter(p -> p.toString().endsWith(".class")).toList();
        }
    }

    @Test
    void nothingOutsideTheOptimizePackageReferencesIt() throws IOException {
        List<Path> offenders =
                mainClassFiles().stream()
                        .filter(p -> !p.toString().replace('\\', '/').contains("/" + OPTIMIZE))
                        .filter(p -> containsText(p, OPTIMIZE))
                        .toList();

        assertThat(offenders).as("classes outside optimize that depend on it").isEmpty();
    }

    @Test
    void theCriteriaPackageStandsOnItsOwn() throws IOException {
        List<Path> criteria =
                mainClassFiles().stream()
                        .filter(p -> p.toString().replace('\\', '/').contains("/eval/criteria/"))
                        .toList();

        assertThat(criteria).isNotEmpty();
        assertThat(criteria).noneMatch(p -> containsText(p, OPTIMIZE));
    }

    @Test
    void theOptimizePackageIsActuallyPresentSoTheRulesAboveAreMeaningful() throws IOException {
        assertThat(mainClassFiles())
                .anyMatch(p -> p.toString().replace('\\', '/').contains("/" + OPTIMIZE));
    }

    private static boolean containsText(Path classFile, String text) {
        try {
            return new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1)
                    .contains(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
