package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.WeaveCLI;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engineering discipline program (D34, program page section 4.5): a newcomer's first hour of
 * Loom must not get harder. Starters 01 to 04 and every {@code weave init} template use none of the
 * program's constructs, load without warnings, and get no finding from a rule the program added.
 * Each phase adds its rule ids and its words to the lists below and re-runs this test.
 */
class SimplePathStaysSimpleTest {

    /** The rules the program has added so far; none may fire on the simple path. */
    private static final Set<String> RULES_ADDED_BY_THE_PROGRAM = Set.of("LP08", "LP09", "LP10", "LA21");

    /** The constructs the program has added so far, as they appear in a script. */
    private static final Pattern PROGRAM_CONSTRUCTS =
            Pattern.compile(
                    "\\bextractor\\s+\\w+\\s*\\{|\\bextract\\s+\\w+\\s+from\\b|\\btranscript\\s*:|\\bshared_prompt\\s*:\\s*none\\b");

    private static final Path STARTERS = Path.of("../starters").toAbsolutePath().normalize();

    @TempDir Path dir;

    private static List<Path> simpleStarters() throws Exception {
        try (Stream<Path> all = Files.list(STARTERS)) {
            return all.filter(p -> p.getFileName().toString().matches("0[1-4]-.*"))
                    .map(p -> p.resolve("main.loom"))
                    .sorted()
                    .toList();
        }
    }

    private List<Path> initTemplates() throws Exception {
        List<Path> scripts = new ArrayList<>();
        for (String template : List.of("pipeline", "approval", "classifier", "empty")) {
            Path project = dir.resolve(template);
            PrintStream old = System.out;
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true));
            try {
                assertThat(WeaveCLI.run(new String[] {"init", template, project.toString(), "--flat"}))
                        .as("weave init " + template)
                        .isZero();
            } finally {
                System.setOut(old);
            }
            try (Stream<Path> found = Files.walk(project)) {
                found.filter(p -> p.toString().endsWith(".loom")).forEach(scripts::add);
            }
        }
        return scripts;
    }

    @Test
    void theSimplePathUsesNoneOfTheProgramsConstructs() throws Exception {
        List<Path> scripts = new ArrayList<>(simpleStarters());
        scripts.addAll(initTemplates());

        assertThat(scripts).hasSizeGreaterThanOrEqualTo(8);
        for (Path script : scripts)
            assertThat(Files.readString(script))
                    .as(script + " is part of the simple path")
                    .doesNotContainPattern(PROGRAM_CONSTRUCTS);
    }

    @Test
    void theSimplePathGetsNoFindingFromARuleTheProgramAdded() throws Exception {
        List<Path> scripts = new ArrayList<>(simpleStarters());
        scripts.addAll(initTemplates());

        for (Path script : scripts) {
            List<String> rules =
                    ReviewCorpusTest.review(script, ReviewSettings.defaults()).stream()
                            .map(ReviewCorpusTest.Mark::rule)
                            .toList();
            assertThat(rules)
                    .as("review of " + script)
                    .doesNotContainAnyElementsOf(RULES_ADDED_BY_THE_PROGRAM);
        }
    }
}
