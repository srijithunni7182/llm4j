package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.guide.Guide;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workflow-patterns and run-debugging skills are text only. They install from the jar, they
 * equal the repository copies, every Loom skeleton in the patterns is a starter that is tested on
 * every build, and the weave commands and options they name exist.
 */
class PatternsAndDebuggingSkillsTest {

    private static final Path SKILLS = Path.of("../../..").resolve(".claude/skills");
    private static final Path PATTERNS = SKILLS.resolve("llm4j-workflow-patterns");
    private static final Path DEBUGGING = SKILLS.resolve("llm4j-run-debugging");
    private static final Path STARTERS = Path.of("../starters");
    private static final int MAX_DESCRIPTION_CHARACTERS = 700;
    private static final int MAX_SKILL_WORDS = 1_200;

    private static List<String> filesIn(Path references) throws IOException {
        try (Stream<Path> s = Files.list(references)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void theListedReferencesAreExactlyTheFilesInTheRepositoryCopies() throws IOException {
        assertThat(filesIn(PATTERNS.resolve("references")))
                .isEqualTo(Guide.PATTERNS_REFERENCES.stream().sorted().toList());
        assertThat(filesIn(DEBUGGING.resolve("references")))
                .isEqualTo(Guide.DEBUG_REFERENCES.stream().sorted().toList());
    }

    @Test
    void installingWritesEveryFileVerbatim(@TempDir Path project) throws IOException {
        assertThat(Guide.installPatternsSkill(project, false))
                .contains("SKILL.md", "references/patterns.md");
        assertThat(Guide.installDebugSkill(project, false))
                .contains("SKILL.md", "references/triage.md", "references/commands.md");

        assertSameFiles(PATTERNS, Guide.patternsFolder(project), Guide.PATTERNS_REFERENCES);
        assertSameFiles(DEBUGGING, Guide.debugFolder(project), Guide.DEBUG_REFERENCES);
    }

    private static void assertSameFiles(Path source, Path installed, List<String> references) {
        assertThat(installed.resolve("SKILL.md")).hasSameTextualContentAs(source.resolve("SKILL.md"));
        for (String ref : references)
            assertThat(installed.resolve("references").resolve(ref))
                    .hasSameTextualContentAs(source.resolve("references").resolve(ref));
    }

    @Test
    void installingAgainWithoutForceLeavesThemAlone(@TempDir Path project) throws IOException {
        Guide.installPatternsSkill(project, false);
        Guide.installDebugSkill(project, false);

        assertThat(Guide.installPatternsSkill(project, false)).isEmpty();
        assertThat(Guide.installDebugSkill(project, false)).isEmpty();
    }

    @Test
    void everyLoomSkeletonIsAStarterThatIsTestedOnEveryBuild() throws IOException {
        String text = Files.readString(PATTERNS.resolve("references/patterns.md"));
        Matcher m =
                Pattern.compile("<!-- starter:([a-z0-9-]+) -->\\n```loom\\n(.*?)\\n```", Pattern.DOTALL)
                        .matcher(text);
        int found = 0;
        while (m.find()) {
            found++;
            String starter = Files.readString(STARTERS.resolve(m.group(1)).resolve("main.loom"));
            assertThat(m.group(2)).as(m.group(1)).isEqualTo(starter.stripTrailing());
        }
        assertThat(found).as("skeletons").isEqualTo(count(text, "```loom"));
        assertThat(found).isGreaterThanOrEqualTo(10);
    }

    private static int count(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void everyPatternNamesTheStagesThatMatter() throws IOException {
        String text = Files.readString(PATTERNS.resolve("references/patterns.md"));
        assertThat(count(text, "**Use it when:**")).isEqualTo(count(text, "```loom"));
        assertThat(count(text, "**Decide with the person:**")).isEqualTo(count(text, "```loom"));
        assertThat(count(text, "Stages that matter:")).isEqualTo(count(text, "```loom"));
    }

    @Test
    void theSkillsStayShortAndTheirDescriptionsStayShort() throws IOException {
        for (Path skill : List.of(PATTERNS, DEBUGGING)) {
            String text = Files.readString(skill.resolve("SKILL.md"));
            String description =
                    text.lines().filter(l -> l.startsWith("description:")).findFirst().orElseThrow();
            assertThat(description.length()).as(skill + " description").isLessThan(MAX_DESCRIPTION_CHARACTERS);
            assertThat(text.split("\\s+").length).as(skill + " words").isLessThan(MAX_SKILL_WORDS);
        }
    }

    @Test
    void everyReferenceTheSkillNamesExists() throws IOException {
        for (Path skill : List.of(PATTERNS, DEBUGGING)) {
            String text = Files.readString(skill.resolve("SKILL.md"));
            Matcher m = Pattern.compile("`references/([a-z0-9-]+\\.md)`").matcher(text);
            while (m.find())
                assertThat(skill.resolve("references").resolve(m.group(1))).as(m.group(1)).exists();
        }
    }

    @Test
    void theWeaveCommandsAndOptionsTheyNameExist() throws IOException {
        var root = WeaveCLI.commandLine();
        List<String> problems = new ArrayList<>();
        for (Path skill : List.of(PATTERNS, DEBUGGING)) {
            List<Path> files = new ArrayList<>();
            files.add(skill.resolve("SKILL.md"));
            try (Stream<Path> s = Files.list(skill.resolve("references"))) {
                s.forEach(files::add);
            }
            for (Path file : files) {
                Matcher m =
                        Pattern.compile("`weave ([a-z][a-z-]*)([^`]*)`").matcher(Files.readString(file));
                while (m.find()) {
                    var command = root.getSubcommands().get(m.group(1));
                    if (command == null) {
                        problems.add(file + ": weave " + m.group(1));
                        continue;
                    }
                    for (String word : m.group(2).split("[ \\[\\]|]+"))
                        if (word.startsWith("--")
                                && !command.getCommandSpec()
                                        .optionsMap()
                                        .containsKey(word.replaceAll("=.*", "")))
                            problems.add(file + ": weave " + m.group(1) + " " + word);
                }
            }
        }
        assertThat(problems).isEmpty();
    }
}
