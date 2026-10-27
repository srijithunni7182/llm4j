package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** R34: what the skill tells a coding agent to run matches the command it runs. */
class GenerateSkillDriftTest {

    private static String skill() throws Exception {
        Path p = Path.of("../../../.claude/skills/llm4j-workflow-guide/SKILL.md");
        return Files.readString(p);
    }

    @Test
    void everyFlagTheSkillGivesToWeaveGenerateIsAFlagItHas() throws Exception {
        String text = skill();
        int from = text.indexOf("## When the user has a description and no script yet");
        int to = text.indexOf("## Review is advice");
        assertThat(from).isPositive();
        String section = text.substring(from, to);
        Set<String> real = new HashSet<>();
        new CommandLine(new GenerateCommand())
                .getCommandSpec()
                .options()
                .forEach(o -> real.addAll(java.util.List.of(o.names())));
        Matcher m = Pattern.compile("(--[a-z][a-z-]+)").matcher(section);
        int seen = 0;
        while (m.find()) {
            seen++;
            assertThat(real).as("the skill names " + m.group(1)).contains(m.group(1));
        }
        assertThat(seen).isGreaterThan(8);
    }

    @Test
    void theSkillSaysWhatEachGateMeansAndWhatIsNotChecked() throws Exception {
        String text = skill();
        for (String gate : new String[] {"G1", "G2", "G3", "G4", "G5"})
            assertThat(text).contains("**" + gate + "**");
        assertThat(text)
                .contains("did **not** check")
                .contains("weave generate --gate-only")
                .contains("--plan-only");
    }

    @Test
    void theExitCodesTheSkillGivesAreTheOnesTheCommandUses() throws Exception {
        String section = skill();
        assertThat(section)
                .contains("Exit 0 done, 3 a gate did not pass")
                .contains("4 the spend cap");
        assertThat(GenerateCommand.class.getDeclaredAnnotation(CommandLine.Command.class).name())
                .isEqualTo("generate");
    }
}
