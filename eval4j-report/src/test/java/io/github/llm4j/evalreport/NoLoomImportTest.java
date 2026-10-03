package io.github.llm4j.evalreport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * LOOM-02/24: only the Loom bridge package may touch Loom; eval4j and the rest of the report stay
 * Loom-free.
 */
class NoLoomImportTest {

    private static List<Path> offenders(Path root, String allowedDir) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(allowedDir))
                    .filter(
                            p -> {
                                try {
                                    return Files.readString(p).contains("io.github.llm4j.loom");
                                } catch (IOException e) {
                                    return true;
                                }
                            })
                    .collect(Collectors.toList());
        }
    }

    @Test
    void eval4jHasNoLoomReferences() throws IOException {
        assertThat(offenders(Path.of("../eval4j/src/main"), "/never/")).isEmpty();
    }

    @Test
    void onlyTheBridgePackageReferencesLoom() throws IOException {
        assertThat(offenders(Path.of("src/main"), "/evalreport/loom/")).isEmpty();
    }
}
