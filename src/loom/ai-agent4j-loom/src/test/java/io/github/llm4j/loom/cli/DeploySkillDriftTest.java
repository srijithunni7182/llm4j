package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * R31 of spec 15: what the deployer skill tells a coding agent to run is a command and flags that
 * exist.
 */
class DeploySkillDriftTest {

    private static String section() throws Exception {
        String text =
                Files.readString(Path.of("../../../.claude/skills/llm4j-loom-deployer/SKILL.md"));
        int from = text.indexOf("## When they want it in a container");
        int to = text.indexOf("## Rules you do not break");
        assertThat(from).isPositive();
        return text.substring(from, to);
    }

    @Test
    void everyFlagTheSkillGivesToWeaveDeployIsOneItHas() throws Exception {
        Set<String> real = new HashSet<>();
        new CommandLine(new DeployCommand())
                .getCommandSpec()
                .options()
                .forEach(o -> real.addAll(List.of(o.names())));
        Matcher m = Pattern.compile("(--[a-z][a-z-]+)").matcher(section());
        int seen = 0;
        while (m.find()) {
            seen++;
            assertThat(real).as("the skill names " + m.group(1)).contains(m.group(1));
        }
        assertThat(seen).isGreaterThan(6);
    }

    @Test
    void theSkillNamesTheCommandAndTheWeaveCommandsExist() throws Exception {
        String s = section();
        assertThat(s)
                .contains("weave deploy")
                .contains("never stops the files")
                .contains("deploy/weave.jar");
        assertThat(WeaveCLI.commandLine().getSubcommands()).containsKeys("deploy", "bindgen");
    }
}
