package io.github.llm4j.loom.generate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.wtest.Coverage;
import io.github.llm4j.loom.wtest.TestRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tool's own cases (D13 (a)): they pass by construction and reach what the script can reach.
 */
class TestSynthTest {

    @TempDir Path tmp;

    private Path copy(String dir) throws IOException {
        Path from = Path.of(dir);
        Path to = tmp.resolve(from.getFileName().toString());
        try (Stream<Path> w = Files.walk(from)) {
            for (Path p : (Iterable<Path>) w.sorted(Comparator.naturalOrder())::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
        try (Stream<Path> w = Files.walk(to)) {
            w.filter(p -> p.toString().endsWith(".test.yaml")).forEach(p -> p.toFile().delete());
        }
        return to;
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "src/main/resources/try/triage",
                "src/main/resources/try/flaky",
                "src/main/resources/try/newsletter",
                "samples/content_factory",
                "samples/boardroom",
                "../../examples/newsletter",
            })
    void everyStepThatCanBeReachedIsReachedAndEveryCasePasses(String project) throws Exception {
        Path dir = copy(project);
        Path script = dir.resolve("main.loom");
        TestSynth.Derived d = TestSynth.derive(script, "../main.loom");
        assertThat(d).as(project).isNotNull();
        Path tests = dir.resolve("tests");
        Files.createDirectories(tests);
        Path file = tests.resolve("derived.test.yaml");
        Files.writeString(file, d.yaml());

        TestRunner.Suite suite = TestRunner.load(script, List.of(file));
        assertThat(suite.problems()).as(project).isEmpty();
        var results = TestRunner.run(suite, null, false, false);
        assertThat(results).isNotEmpty();
        assertThat(results.stream().filter(r -> !r.passed()).toList()).as(project).isEmpty();
        Coverage c = TestRunner.coverage(suite, results);
        System.out.println(
                project
                        + ": "
                        + d.cases()
                        + " cases, tried "
                        + d.tried()
                        + ", "
                        + c.percent()
                        + "%");
        assertThat(c.percent()).as(project + " " + c.missed()).isGreaterThanOrEqualTo(70);
    }
}
