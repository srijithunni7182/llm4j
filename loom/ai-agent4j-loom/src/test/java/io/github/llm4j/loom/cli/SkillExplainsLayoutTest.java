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

    @Test
    void theSkillRoundsUpWithABuildAReportTheDashboardAndOnlyThenTheSteppingStoneToSpending() throws Exception {
        String skill = Files.readString(Path.of("../../.claude/skills/llm4j-workflow-guide/SKILL.md"));
        int roundUp = skill.indexOf("**Round up before you talk about money.**");
        assertThat(roundUp).isPositive();
        String stage = skill.substring(roundUp, skill.indexOf("## Explain as you go"));
        assertThat(stage).contains("Run `mvn test`").contains("PROJECT_REPORT.md").contains("target/eval4j/report/index.html")
                .contains("Declared, not evaluated").contains("the wiring only, not the quality")
                .contains("# <project>: what was built").contains("## Set up and run it for real");
        // the order the user asked for: build, report, dashboard, then how to set up and run for real, which is last
        assertThat(stage.indexOf("**Build it.**")).isLessThan(stage.indexOf("**Write `PROJECT_REPORT.md`**"));
        assertThat(stage.indexOf("**Write `PROJECT_REPORT.md`**")).isLessThan(stage.indexOf("**Open the eval4j dashboard.**"));
        assertThat(stage.indexOf("**Open the eval4j dashboard.**")).isLessThan(stage.indexOf("**Then, last, how to set up and run it for real.**"));
        assertThat(stage).contains("never ask for it in chat").contains("run none of it without a yes");
    }

    @Test
    void theGeneratedTestsExportTheDashboardAndRecordWiringNotQuality() throws Exception {
        String pom = Files.readString(Path.of("src/main/resources/templates/_java-tests/pom.xml"));
        String test = Files.readString(Path.of("src/main/resources/templates/_java-tests/src/test/java/starter/ScriptWiringTest.java"));
        assertThat(pom).contains("<artifactId>eval4j-report</artifactId>");
        assertThat(test).contains("@ExtendWith(EvalReportExtension.class)").contains("declareDataset").contains("\"wiring\"")
                .doesNotContain("dimension(\"safety\")").doesNotContain("dimension(\"tone\")");
    }
}
