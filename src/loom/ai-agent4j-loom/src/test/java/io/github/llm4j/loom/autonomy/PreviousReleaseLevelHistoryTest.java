package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verification G2: for each decision script in the repository, the level history for a fixed series of cases is identical under the previous release and
 * this one. The goldens in {@code src/test/resources/previous-release/g2} were written by the previous release (built from the commit before this work)
 * running {@link LevelHistoryDriver}; this test runs the same driver on the same scripts here and compares line by line.
 */
@Tag("L7")
class PreviousReleaseLevelHistoryTest {

    @TempDir
    Path dir;

    static final Path GOLDEN = Path.of("src/test/resources/previous-release/g2");

    static Stream<String> scripts() throws Exception {
        try (Stream<Path> files = Files.list(GOLDEN)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".loom")).map(n -> n.substring(0, n.length() - 5)).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scripts")
    void everyCaseRunsAtTheSameLevelAndEveryChangeAndProposalIsTheSame(String name) throws Exception {
        String script = Files.readString(GOLDEN.resolve(name + ".loom"));
        List<String> expected = Files.readAllLines(GOLDEN.resolve(name + ".history"));
        List<String> actual = LevelHistoryDriver.history(dir, script, 320);
        if (!actual.equals(expected)) {
            Path out = Path.of("target/g2-actual");                        // kept so a difference can be read with diff
            Files.createDirectories(out);
            Files.write(out.resolve(name + ".history"), actual);
        }
        assertThat(actual).as(name).isEqualTo(expected);
    }
}
