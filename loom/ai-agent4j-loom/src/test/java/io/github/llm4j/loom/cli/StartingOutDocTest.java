package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** R3 and R10 of loom-onboarding: the reference and the index describe init, check and guide, and every option they show exists. */
class StartingOutDocTest {

    @Test
    void theReferenceAndTheIndexDescribeInitCheckAndGuide() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"));
        String llms = Files.readString(Path.of("../../llms.txt"));

        assertThat(guide).contains("### 5. Starting, Checking and Reading the Guide").contains("weave init pipeline").contains("--with-java-tests")
                .contains("--no-env").contains("--format json").contains("--strict").contains("weave guide --install-skill");
        assertThat(llms).contains("**Starting out:**").contains("weave init").contains("--no-env").contains("weave guide");
    }

    @Test
    void everyOptionShownInThatSectionExistsOnItsCommand() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"));
        String section = guide.substring(guide.indexOf("### 5. Starting, Checking"), guide.indexOf("`weave check` warns"));
        CommandLine root = WeaveCLI.commandLine();
        for (String line : section.split("\n")) {
            var m = java.util.regex.Pattern.compile("^weave ([a-z]+)(.*?)(#.*)?$").matcher(line);
            if (!m.find()) continue;
            CommandLine sub = root.getSubcommands().get(m.group(1));
            assertThat(sub).as(line).isNotNull();
            var o = java.util.regex.Pattern.compile("--[a-z-]+").matcher(m.group(2));
            while (o.find()) assertThat(sub.getCommandSpec().optionsMap()).as(line).containsKey(o.group());
        }
        assertThat(List.of("init", "check", "guide")).allMatch(root.getSubcommands()::containsKey);
    }

    @Test
    void theReferenceTheIndexAndTheSkillDescribeExplainNextAndRecipes() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"));
        String llms = Files.readString(Path.of("../../llms.txt"));
        String skill = Files.readString(Path.of("../../.claude/skills/llm4j-workflow-guide/SKILL.md"));

        assertThat(guide).contains("weave explain main.loom").contains("weave next").contains("weave guide recipes").contains("rejects a `delegate` or `broadcast` to an agent the script does not define");
        assertThat(llms).contains("weave explain script.loom").contains("weave next").contains("weave guide recipes");
        assertThat(skill).contains("weave next").contains("weave explain").contains("weave guide recipes");
        assertThat(WeaveCLI.commandLine().getSubcommands()).containsKeys("explain", "next");
    }
}
