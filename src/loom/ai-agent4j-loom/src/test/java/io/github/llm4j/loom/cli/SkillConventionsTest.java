package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules every skill follows live in one text that each skill carries a copy of. The copies must
 * not drift, every skill must point to them, and they must reach an installed skill.
 */
class SkillConventionsTest {

    private static final List<String> SKILLS =
            List.of(
                    "llm4j-workflow-guide",
                    "llm4j-loom-deployer",
                    "llm4j-harness-design",
                    "llm4j-eval-design");
    private static final Path SKILLS_DIR = Path.of("../../../.claude/skills");
    private static final String FILE = "references/conventions.md";

    private static String conventions(String skill) throws IOException {
        return Files.readString(SKILLS_DIR.resolve(skill).resolve(FILE));
    }

    @Test
    void everySkillCarriesTheSameConventions() throws IOException {
        String first = conventions(SKILLS.get(0));
        for (String skill : SKILLS) assertThat(conventions(skill)).as(skill).isEqualTo(first);
    }

    @Test
    void everySkillSendsItsReaderToTheConventions() throws IOException {
        for (String skill : SKILLS)
            assertThat(Files.readString(SKILLS_DIR.resolve(skill).resolve("SKILL.md")))
                    .as(skill)
                    .contains("`references/conventions.md`");
    }

    @Test
    void theConventionsNameOnlyWeaveCommandsAndOptionsThatExist() throws IOException {
        var root = WeaveCLI.commandLine();
        var m =
                java.util.regex.Pattern.compile("`weave ([a-z][a-z-]*)((?: [^`]*)?)`")
                        .matcher(conventions(SKILLS.get(0)));
        while (m.find()) {
            var command = root.getSubcommands().get(m.group(1));
            assertThat(command).as("weave " + m.group(1)).isNotNull();
            for (String word : m.group(2).split(" "))
                if (word.startsWith("--"))
                    assertThat(command.getCommandSpec().optionsMap())
                            .as("weave " + m.group(1) + " " + word)
                            .containsKey(word);
        }
    }

    @Test
    void everyInstalledSkillHasThem(@TempDir Path project) throws IOException {
        io.github.llm4j.loom.guide.Guide.installSkill(project, true);
        io.github.llm4j.loom.guide.Guide.installDeployerSkill(project, true);
        io.github.llm4j.loom.guide.Guide.installHarnessSkill(project, true);
        io.github.llm4j.loom.guide.Guide.installEvalSkill(project, true);

        for (String skill : SKILLS)
            assertThat(project.resolve(".claude/skills").resolve(skill).resolve(FILE))
                    .as(skill)
                    .hasSameTextualContentAs(SKILLS_DIR.resolve(skill).resolve(FILE));
    }

    @Test
    void theSkillsDescriptionsDoNotCompeteForTheSameRequests() throws IOException {
        List<Set<String>> words = new java.util.ArrayList<>();
        for (String skill : SKILLS) {
            String text = Files.readString(SKILLS_DIR.resolve(skill).resolve("SKILL.md"));
            String description =
                    text.lines().filter(l -> l.startsWith("description:")).findFirst().orElseThrow();
            words.add(
                    Stream.of(description.toLowerCase().split("[^a-z]+"))
                            .filter(w -> w.length() > 5)
                            .collect(Collectors.toSet()));
        }
        for (int a = 0; a < words.size(); a++)
            for (int b = a + 1; b < words.size(); b++) {
                Set<String> shared = new java.util.HashSet<>(words.get(a));
                shared.retainAll(words.get(b));
                double overlap = shared.size() / (double) Math.min(words.get(a).size(), words.get(b).size());
                assertThat(overlap).as(SKILLS.get(a) + " vs " + SKILLS.get(b) + " " + shared).isLessThan(0.5);
            }
    }
}
