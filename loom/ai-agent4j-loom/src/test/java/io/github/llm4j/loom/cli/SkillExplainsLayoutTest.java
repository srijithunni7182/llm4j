package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The skill tells the agent to explain the project it makes and each stage, and no longer says a script-only project has no Maven or no tests. */
class SkillExplainsLayoutTest {

    @Test
    void theSkillSaysWhatEachPartOfTheStarterIsAndToExplainAsItGoes() throws Exception {
        String skill = Files.readString(Path.of("../../.claude/skills/llm4j-workflow-guide/SKILL.md"));
        assertThat(skill).contains("makes a **Maven project**")
                .contains("src/main/resources/main.loom").contains("src/test/resources/eval/golden/")
                .contains("golden dataset, which is the eval tests")
                .contains("`src/test/java/starter/` are eval4j JUnit tests")
                .contains("Never copy files out of a scratch")
                .contains("## Explain as you go").contains("expected_output_not_contains")
                .doesNotContain("no Java, no Maven").doesNotContain("--with-java-tests` adds a test module that works");
    }

    @Test
    void theGuideNamesTheMavenPathsAndTheRecipesSayWhereTheFilesAre() throws Exception {
        assertThat(Files.readString(Path.of("../../docs/guide/README.md"))).contains("Maven project").contains("src/test/resources/eval/golden");
        assertThat(Files.readString(Path.of("../../docs/guide/RECIPES.md"))).contains("src/main/resources/main.loom");
        assertThat(Files.readString(Path.of("LOOM_GUIDE.md"))).contains("--flat").contains("src/main/resources/main.loom --no-env");
    }
}
