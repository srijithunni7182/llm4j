package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.guide.Guide;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The harness-design skill is text only: it ships in the jar, installs with the other two (or
 * alone), and equals the repository copy.
 */
class HarnessSkillTest {

    static final Path SKILL = Path.of("../../..").resolve(".claude/skills/llm4j-harness-design");

    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    int guide(String... args) {
        GuideCommand c = new GuideCommand();
        new CommandLine(c).parseArgs(args);
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "yes",
                        new PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8),
                        new PrintStream(err, true),
                        Clock.systemUTC(),
                        d -> {},
                        c2 ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        return GuideCommand.guide(c, env);
    }

    @Test
    void theListedReferencesAreExactlyTheFilesInTheRepositorySkill() throws Exception {
        try (var s = Files.list(SKILL.resolve("references"))) {
            assertThat(s.map(p -> p.getFileName().toString()).sorted().toList())
                    .isEqualTo(Guide.HARNESS_REFERENCES.stream().sorted().toList());
        }
    }

    @Test
    void installingOnlyTheHarnessSkillWritesItsFilesVerbatim() throws Exception {
        assertThat(guide("--install-skill", dir.toString(), "--skill", "harness")).isZero();
        Path folder = Guide.harnessFolder(dir);
        assertThat(Files.readString(folder.resolve("SKILL.md")))
                .isEqualTo(Files.readString(SKILL.resolve("SKILL.md")))
                .startsWith("---\nname: llm4j-harness-design\n");
        for (String ref : Guide.HARNESS_REFERENCES)
            assertThat(Files.readString(folder.resolve("references").resolve(ref)))
                    .as(ref)
                    .isEqualTo(Files.readString(SKILL.resolve("references").resolve(ref)));
        assertThat(Guide.skillFolder(dir)).doesNotExist();
        assertThat(Guide.deployerFolder(dir)).doesNotExist();
    }

    @Test
    void allInstallsFourSkillsAndASecondRunWithoutForceInstallsNone() {
        assertThat(guide("--install-skill", dir.toString())).isZero();
        assertThat(Guide.harnessFolder(dir).resolve("SKILL.md")).exists();
        assertThat(Guide.skillFolder(dir).resolve("SKILL.md")).exists();
        assertThat(Guide.deployerFolder(dir).resolve("SKILL.md")).exists();
        assertThat(Guide.evalFolder(dir).resolve("SKILL.md")).exists();
        assertThat(guide("--install-skill", dir.toString())).isEqualTo(2);
        assertThat(err.toString()).contains("already in").contains("--force");
        assertThat(guide("--install-skill", dir.toString(), "--skill", "harness", "--force"))
                .isZero();
    }

    @Test
    void anUnknownSkillNameListsTheFourChoices() {
        assertThat(guide("--install-skill", dir.toString(), "--skill", "nope")).isEqualTo(2);
        assertThat(err.toString()).contains("harness, workflow, deployer or all");
    }

    @Test
    void theSkillPointsAtNoRepositoryPath() throws Exception {
        String all = Files.readString(SKILL.resolve("SKILL.md"));
        for (String ref : Guide.HARNESS_REFERENCES)
            all += Files.readString(SKILL.resolve("references").resolve(ref));
        assertThat(all)
                .doesNotContain("](../")
                .doesNotContain("docs/guide/")
                .doesNotContain("docs/deploy/");
    }
}
