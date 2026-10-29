package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F13: the deployer skill's scripts found their jars in {@code bin/} or a build in the repository
 * and nowhere else, and said nothing about how to get them. One lookup order, the same in the shell
 * and PowerShell versions: the variable, the editor extension's jar, {@code bin/}, the fetched tools
 * folder, a build. Fragile: it runs the real scripts.
 */
@Tag("fragile")
class ToolLookupOrderTest {
    private static final Path SKILL =
            Path.of("../../../.claude/skills/llm4j-loom-deployer").toAbsolutePath().normalize();

    @TempDir Path root;

    Path skill;
    Path home;

    private void layout() throws Exception {
        skill = Files.createDirectories(root.resolve("repo/.claude/skills/llm4j-loom-deployer"));
        Files.createDirectories(skill.resolve("scripts"));
        for (String f : new String[] {"lib.sh", "lib.ps1"})
            Files.copy(SKILL.resolve("scripts").resolve(f), skill.resolve("scripts").resolve(f));
        home = Files.createDirectories(root.resolve("home"));
    }

    private static Path touch(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        return file;
    }

    private record Out(int exit, String text) {}

    private Out weaveJar(Map<String, String> env) throws Exception {
        ProcessBuilder pb =
                new ProcessBuilder("bash", "-c", "source " + skill.resolve("scripts/lib.sh") + "; weave_jar")
                        .redirectErrorStream(true);
        pb.environment().put("HOME", home.toString());
        pb.environment().remove("WEAVE_JAR");
        pb.environment().putAll(env);
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        return new Out(p.waitFor(), out.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void eachSourceBeatsTheOnesBelowItAndNothingFoundSaysHowToGetTheJar() throws Exception {
        assumeTrue(Files.exists(Path.of("/bin/bash")) || Files.exists(Path.of("/usr/bin/bash")));
        layout();

        Out none = weaveJar(Map.of());
        assertThat(none.exit()).isNotZero();
        assertThat(none.text()).contains("jbang app install").contains("WEAVE_JAR");

        Path build = touch(root.resolve("repo/src/loom/ai-agent4j-loom/target/ai-agent4j-loom-6.2.0-cli.jar"));
        assertThat(weaveJar(Map.of()).text()).isEqualTo(build.toString().replace(root + "/repo/", skill + "/../../../"));
    }

    @Test
    void theFetchedToolsBeatABuildTheSkillBinBeatsTheFetchedToolsAndTheExtensionBeatsBin()
            throws Exception {
        assumeTrue(Files.exists(Path.of("/bin/bash")) || Files.exists(Path.of("/usr/bin/bash")));
        layout();
        touch(root.resolve("repo/src/loom/ai-agent4j-loom/target/ai-agent4j-loom-6.2.0-cli.jar"));

        Path fetched = touch(home.resolve(".llm4j/tools/6.1.0/ai-agent4j-loom-6.1.0-cli.jar"));
        assertThat(weaveJar(Map.of()).text()).isEqualTo(fetched.toString());

        Path bin = touch(skill.resolve("bin/weave.jar"));
        assertThat(weaveJar(Map.of()).text()).isEqualTo(bin.toString());

        Path ext =
                touch(home.resolve(".vscode/extensions/srijithunni7182.vscode-llm4j-loom-1.1.0/bin/weave.jar"));
        assertThat(weaveJar(Map.of()).text()).isEqualTo(ext.toString());

        Path given = touch(root.resolve("elsewhere/mine.jar"));
        assertThat(weaveJar(Map.of("WEAVE_JAR", given.toString())).text()).isEqualTo(given.toString());
    }

    @Test
    void theNewestFetchedVersionWins() throws Exception {
        assumeTrue(Files.exists(Path.of("/bin/bash")) || Files.exists(Path.of("/usr/bin/bash")));
        layout();
        Path older = touch(home.resolve(".llm4j/tools/6.0.0/ai-agent4j-loom-6.0.0-cli.jar"));
        older.getParent().toFile().setLastModified(System.currentTimeMillis() - 100_000);
        Path newer = touch(home.resolve(".llm4j/tools/6.1.0/ai-agent4j-loom-6.1.0-cli.jar"));

        assertThat(weaveJar(Map.of()).text()).isEqualTo(newer.toString());
    }

    @Test
    void thePowerShellScriptNamesTheSameFiveSourcesInTheSameOrder() throws Exception {
        String sh = Files.readString(SKILL.resolve("scripts/lib.sh"));
        String ps = Files.readString(SKILL.resolve("scripts/lib.ps1"));
        for (String text : new String[] {sh, ps}) {
            int ext = text.indexOf("vscode-llm4j-loom");
            int bin = text.indexOf("bin", ext);
            int tools = text.indexOf(".llm4j", bin);
            int repo = text.indexOf("src", tools);
            assertThat(ext).isPositive();
            assertThat(ext).isLessThan(bin);
            assertThat(bin).isLessThan(tools);
            assertThat(tools).isLessThan(repo);
        }
    }
}
