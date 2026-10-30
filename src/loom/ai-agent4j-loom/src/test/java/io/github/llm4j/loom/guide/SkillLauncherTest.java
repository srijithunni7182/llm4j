package io.github.llm4j.loom.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F14: a coding agent in a plain terminal has no {@code weave} command. The skill ships a launcher
 * that finds one, or prints the one install line.
 */
class SkillLauncherTest {
    @TempDir Path dir;

    @Test
    void installingTheSkillWritesTheLauncherAndMakesItExecutable() throws Exception {
        var written = Guide.installSkill(dir, false);

        assertThat(written).contains("scripts/weave", "scripts/weave.cmd");
        Path launcher = Guide.skillFolder(dir).resolve("scripts/weave");
        assertThat(launcher).exists();
        assertThat(Files.isExecutable(launcher)).isTrue();
        assertThat(Files.readString(launcher))
                .contains("jbang app install weave@srijithunni7182/llm4j");
    }

    @Test
    void theSkillTextTellsAnAgentToUseTheLauncherWhenWeaveIsNotOnThePath() {
        assertThat(Guide.skill().orElseThrow()).contains("scripts/weave");
    }

    private record Ran(int exit, String out) {}

    private Ran launch(Path home, Path pathDir, String... args) throws Exception {
        Guide.installSkill(dir, true);
        Path launcher = Guide.skillFolder(dir).resolve("scripts/weave");
        List<String> command = new ArrayList<>(List.of("/bin/sh", launcher.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("HOME", home.toString());
        pb.environment().put("PATH", pathDir + ":/usr/bin:/bin");
        pb.environment().remove("WEAVE_JAR");
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        return new Ran(p.waitFor(), out.toString(StandardCharsets.UTF_8));
    }

    @Tag("fragile")
    @Test
    void withNoWeaveAnywhereItPrintsTheInstallLineAndStops() throws Exception {
        assumeTrue(Files.exists(Path.of("/bin/sh")));
        Path home = Files.createDirectories(dir.resolve("home"));
        Path empty = Files.createDirectories(dir.resolve("emptybin"));

        Ran ran = launch(home, empty, "--version");

        assertThat(ran.exit()).isEqualTo(2);
        assertThat(ran.out())
                .contains("jbang app install weave@srijithunni7182/llm4j")
                .contains("VS Code");
    }

    @Tag("fragile")
    @Test
    void aWeaveOnThePathIsRunWithTheArguments() throws Exception {
        assumeTrue(Files.exists(Path.of("/bin/sh")));
        Path home = Files.createDirectories(dir.resolve("home"));
        Path bin = Files.createDirectories(dir.resolve("realbin"));
        Path stub = bin.resolve("weave");
        Files.writeString(stub, "#!/bin/sh\necho \"stub weave $*\"\n");
        stub.toFile().setExecutable(true);

        Ran ran = launch(home, bin, "check", "main.loom");

        assertThat(ran.exit()).isZero();
        assertThat(ran.out()).contains("stub weave check main.loom");
    }
}
