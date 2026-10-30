package io.github.llm4j.loom.install;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F13: the skill installed without the jars its scripts launch, and said nothing. The install ends
 * with what is here and what is missing, and never says "ready" when a script cannot run.
 */
class InstallSkillSummaryTest {
    @TempDir Path dir;

    private Path home() throws Exception {
        return Files.createDirectories(dir.resolve("home"));
    }

    private static Path touch(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        return file;
    }

    @Test
    void withNothingFetchedFabricAndInspectAreMissingAndTheCommandToGetThemIsGiven() throws Exception {
        Path skill = Files.createDirectories(dir.resolve("skill"));

        List<String> lines =
                InstallSummary.lines(home(), Map.<String, String>of()::get, skill, "6.2.0-SNAPSHOT (development)");
        String text = String.join("\n", lines);

        assertThat(text)
                .contains("weave")
                .contains("found")
                .contains("Fabric")
                .contains("missing: run `weave tools fetch fabric`")
                .contains("missing: run `weave tools fetch inspect`")
                .doesNotContain("ready to run");
        assertThat(text).contains("will stop");
    }

    @Test
    void whenEverythingIsFoundItSaysSoAndNamesWhere() throws Exception {
        Path home = home();
        Path skill = Files.createDirectories(dir.resolve("skill"));
        Path fabric = touch(home.resolve(".llm4j/tools/6.1.0/loom-fabric-6.1.0-all.jar"));
        touch(home.resolve(".llm4j/tools/6.1.0/loom-inspect-6.1.0.jar"));
        touch(home.resolve(".llm4j/tools/6.1.0/loom-inspect-core-6.1.0.jar"));

        String text =
                String.join(
                        "\n",
                        InstallSummary.lines(home, Map.<String, String>of()::get, skill, "6.1.0 (release)"));

        assertThat(text).contains(fabric.toString()).contains("ready to run").doesNotContain("missing");
    }

    @Test
    void aJarInTheSkillsBinOrNamedByAVariableCounts() throws Exception {
        Path skill = Files.createDirectories(dir.resolve("skill"));
        touch(skill.resolve("bin/fabric.jar"));
        Path given = touch(dir.resolve("mine/inspect.jar"));
        Path givenCore = touch(dir.resolve("mine/core.jar"));

        String text =
                String.join(
                        "\n",
                        InstallSummary.lines(
                                home(),
                                Map.of("INSPECT_JAR", given.toString(), "INSPECT_CORE_JAR", givenCore.toString())::get,
                                skill,
                                "6.1.0 (release)"));

        assertThat(text).doesNotContain("missing").contains("INSPECT_JAR");
    }
}
