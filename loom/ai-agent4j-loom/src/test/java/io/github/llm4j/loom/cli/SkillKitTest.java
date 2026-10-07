package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.guide.Guide;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** R9.2 and R10.1 of loom-onboarding: the skill names only commands, options and chapters that exist, and sends nobody to the repository. */
class SkillKitTest {

    static String skill() {
        return Guide.read("skill/SKILL.md").orElseThrow();
    }

    @Test
    void everyWeaveCommandTheSkillNamesExists() {
        CommandLine root = WeaveCLI.commandLine();
        List<String> missing = new ArrayList<>();
        Matcher m = Pattern.compile("weave ([a-z][a-z-]*)").matcher(skill());
        while (m.find()) {
            String word = m.group(1);
            if (!root.getSubcommands().containsKey(word) && !Set.of("is", "and", "reads", "does", "in", "check").contains(word)) missing.add(word);
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void everyOptionOnAWeaveCommandLineExistsOnThatCommand() {
        CommandLine root = WeaveCLI.commandLine();
        List<String> missing = new ArrayList<>();
        for (String line : skill().split("\n")) {
            Matcher m = Pattern.compile("weave ([a-z][a-z-]*)\\b([^`]*)").matcher(line);
            if (!m.find() || !root.getSubcommands().containsKey(m.group(1))) continue;
            CommandLine sub = root.getSubcommands().get(m.group(1));
            Matcher nested = Pattern.compile("^\\s+([a-z]+)\\b").matcher(m.group(2));
            if (nested.find() && sub.getSubcommands().containsKey(nested.group(1))) sub = sub.getSubcommands().get(nested.group(1));
            String rest = m.group(2).replaceAll("#.*", "");
            Matcher o = Pattern.compile("(--[a-z][a-z-]*)").matcher(rest);
            while (o.find()) if (!sub.getCommandSpec().optionsMap().containsKey(o.group(1))) missing.add(m.group(1) + " " + o.group(1));
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void everyChapterAndGuidePageTheSkillNamesIsInTheJar() {
        String s = skill();
        Matcher chapters = Pattern.compile("docs/guide/(\\d\\d-[a-z-]+\\.md)").matcher(s);
        int found = 0;
        while (chapters.find()) {
            found++;
            assertThat(Guide.find(chapters.group(1))).as(chapters.group(1)).isPresent();
        }
        assertThat(found).isEqualTo(11);
        Matcher pages = Pattern.compile("weave guide ([a-z0-9]+)").matcher(s);
        while (pages.find()) {
            if (pages.group(1).equals("lists")) continue;
            assertThat(Guide.find(pages.group(1))).as("weave guide " + pages.group(1)).isPresent();
        }
    }

    @Test
    void theInstalledSkillSendsNobodyToTheRepository() {
        // `mvn test` is fine: it is the build of the project weave init makes. Only the framework repository's own build lines are out.
        String installed = Guide.skill().orElseThrow();
        assertThat(installed).doesNotContain("docs/guide/").contains("references/");
        for (String repoOnly : List.of("examples/", "eval4j/src", "loom/ai-agent4j", "eval/run-all", "mvn -pl", "mvn -q -pl", "hexamind-hub", "samples/", "scripts/")) {
            assertThat(installed).as(repoOnly).doesNotContain(repoOnly);
        }
    }

    @Test
    void theSkillCoversTheKitQuestionsAndPitfalls() {
        String s = skill();
        assertThat(s).contains("weave secrets create").contains("weave secrets set").contains("secret.NAME").contains("never ask the user to paste a key into the chat")
                .contains(".env").contains("cp .env.example .env").contains("Never the project's `.env` on a server").contains("Never read, open, print or paste the contents of `.env`");
        assertThat(s).contains("Do you want tests first?").contains("weave init").contains("Tests run: 0").contains("--no-env");
    }
}
