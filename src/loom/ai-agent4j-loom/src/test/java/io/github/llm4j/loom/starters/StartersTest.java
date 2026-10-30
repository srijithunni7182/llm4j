package io.github.llm4j.loom.starters;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.WeaveCLI;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The Loom starter set ({@code src/loom/starters}): every starter loads, passes its own hermetic
 * tests, and is shown truthfully in its README (the script itself, and the graph {@code weave graph}
 * draws now), so a starter cannot quietly rot.
 */
class StartersTest {

    private static final Path STARTERS = Path.of("../starters").toAbsolutePath().normalize();

    /** Starters with no tests on purpose, and why (see the starter's README). */
    private static final Set<String> NO_TESTS = Set.of("09-handbook-qa");

    private static final int MIN_COVERAGE_PERCENT = 80;

    private static List<Path> starters() throws Exception {
        try (Stream<Path> all = Files.list(STARTERS)) {
            return all.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("\\d\\d-.*"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void thereAreFifteenStartersNumberedFromOneWithoutGaps() throws Exception {
        List<String> names = starters().stream().map(p -> p.getFileName().toString()).toList();

        assertThat(names).hasSize(15);
        for (int i = 0; i < names.size(); i++)
            assertThat(names.get(i)).startsWith(String.format("%02d-", i + 1));
    }

    @Test
    void everyStarterHasItsFiles() throws Exception {
        for (Path dir : starters()) {
            String name = dir.getFileName().toString();
            assertThat(dir.resolve("main.loom")).as(name + " main.loom").isRegularFile();
            assertThat(dir.resolve("README.md")).as(name + " README.md").isRegularFile();
            assertThat(dir.resolve(".env.example")).as(name + " .env.example").isRegularFile();
            if (!NO_TESTS.contains(name)) {
                try (Stream<Path> tests = Files.list(dir.resolve("tests"))) {
                    assertThat(tests.filter(t -> t.toString().endsWith(".test.yaml")).count())
                            .as(name + " test files")
                            .isPositive();
                }
            }
        }
    }

    @Test
    void everyStarterLoadsWithoutWarnings() throws Exception {
        for (Path dir : starters()) {
            Output out = weave("check", dir.resolve("main.loom").toString(), "--no-env");
            assertThat(out.exit).as(dir.getFileName() + ": " + out.text).isZero();
            assertThat(out.text).as(dir.getFileName() + " has no warnings").doesNotContain("warning");
        }
    }

    @Test
    void everyStarterPassesItsOwnTestsAndReachesMostOfItsSteps() throws Exception {
        for (Path dir : starters()) {
            if (NO_TESTS.contains(dir.getFileName().toString())) continue;
            Output out =
                    weave(
                            "test",
                            dir.resolve("main.loom").toString(),
                            "--min-coverage",
                            Integer.toString(MIN_COVERAGE_PERCENT));
            assertThat(out.exit).as(dir.getFileName() + ": " + out.text).isZero();
        }
    }

    @Test
    void theScriptInEachReadmeIsTheFileItself() throws Exception {
        for (Path dir : starters()) {
            String readme = Files.readString(dir.resolve("README.md"), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(readme);
            assertThat(m.find()).as(dir.getFileName() + " shows its script").isTrue();
            assertThat(m.group(1).strip())
                    .as(dir.getFileName() + " README script")
                    .isEqualTo(Files.readString(dir.resolve("main.loom")).strip());
        }
    }

    @Test
    void thePictureInEachReadmeIsWhatWeaveGraphDrawsNow() throws Exception {
        for (Path dir : starters()) {
            String readme = Files.readString(dir.resolve("README.md"), StandardCharsets.UTF_8);
            Matcher m =
                    Pattern.compile(
                                    "<!-- graph:start -->\\n```mermaid\\n(.*?)```\\n<!-- graph:end -->",
                                    Pattern.DOTALL)
                            .matcher(readme);
            assertThat(m.find()).as(dir.getFileName() + " has a picture").isTrue();
            Output drawn =
                    weave(
                            "graph",
                            dir.resolve("main.loom").toString(),
                            "--workflow",
                            workflowOf(dir),
                            "--format",
                            "mermaid");
            String expected =
                    drawn.text.lines().filter(l -> !l.startsWith("%%")).reduce("", (a, b) -> a + b + "\n");
            assertThat(m.group(1))
                    .as(dir.getFileName() + " picture is out of date: run scripts/make-starter-diagrams.sh")
                    .isEqualTo(expected);
        }
    }

    @Test
    void anEnvExampleNamesKeysAndHoldsNone() throws Exception {
        for (Path dir : starters()) {
            for (String line : Files.readAllLines(dir.resolve(".env.example"))) {
                if (line.isBlank() || line.startsWith("#")) continue;
                assertThat(line).as(dir.getFileName() + " .env.example").matches("[A-Z][A-Z0-9_]*=");
            }
        }
    }

    @Test
    void theIndexListsEveryStarter() throws Exception {
        String index = Files.readString(STARTERS.resolve("README.md"));
        for (Path dir : starters()) assertThat(index).contains("(" + dir.getFileName() + "/)");
    }

    private static String workflowOf(Path dir) throws Exception {
        Path tests = dir.resolve("tests/main.test.yaml");
        if (!Files.exists(tests)) return "Main";
        return Files.readAllLines(tests).stream()
                .filter(l -> l.startsWith("workflow:"))
                .map(l -> l.substring("workflow:".length()).strip())
                .findFirst()
                .orElse("Main");
    }

    private record Output(int exit, String text) {}

    /** Runs a weave command in this process and keeps what it printed. */
    private static synchronized Output weave(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(sink);
        System.setErr(sink);
        try {
            int exit = WeaveCLI.run(args);
            return new Output(exit, captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
