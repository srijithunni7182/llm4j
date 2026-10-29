package io.github.llm4j.loom.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.BuildIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F10 and F11: two jars both said {@code 6.0.0} and behaved differently, and nothing said so.
 * {@code weave doctor} lists the Loom jars it can reach and names two that share a version but are
 * different builds. Local files only; silent when they all agree.
 */
class DoctorJarsTest {
    @TempDir Path home;

    private Path jar(Path file, String version, String kind, String commit, String time)
            throws Exception {
        Files.createDirectories(file.getParent());
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().putValue("Implementation-Version", version);
        if (kind != null) m.getMainAttributes().putValue("Build-Kind", kind);
        if (commit != null) m.getMainAttributes().putValue("Build-Commit", commit);
        m.getMainAttributes().putValue("Build-Time", time);
        try (var out = new JarOutputStream(Files.newOutputStream(file), m)) {}
        return file;
    }

    private static JarDiscovery.Found running(String version, String kind, String commit) {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().putValue("Implementation-Version", version);
        m.getMainAttributes().putValue("Build-Kind", kind);
        m.getMainAttributes().putValue("Build-Commit", commit);
        m.getMainAttributes().putValue("Build-Time", "2026-10-29T15:30:00Z");
        return new JarDiscovery.Found("the weave you are running", null, BuildIdentity.of(m));
    }

    private Path m2(String version) {
        return home.resolve(".m2/repository/io/github/srijithunni7182/ai-agent4j-loom")
                .resolve(version)
                .resolve("ai-agent4j-loom-" + version + ".jar");
    }

    @Test
    void anOlderCopyThatSharesTheRunningVersionButIsAnotherBuildIsNamed() throws Exception {
        jar(m2("6.0.0"), "6.0.0", "release", "db5b43c7", "2026-10-17T09:00:00Z");

        var found =
                JarDiscovery.discover(home, Map.<String, String>of()::get, running("6.0.0", "development", "3d683a35"));
        var check = Doctor.jars(found);

        assertThat(check).isPresent();
        assertThat(check.get().status()).isEqualTo(Doctor.Status.WARN);
        assertThat(check.get().reason())
                .contains("both say 6.0.0")
                .contains("ai-agent4j-loom-6.0.0.jar")
                .contains("release")
                .contains("development");
        assertThat(check.get().action()).contains("Remove").contains("WEAVE_JAR");
    }

    @Test
    void jarsThatAreTheSameBuildSayNothing() throws Exception {
        jar(m2("6.2.0-SNAPSHOT"), "6.2.0-SNAPSHOT", "development", "3d683a35", "2026-10-29T15:30:00Z");

        var found =
                JarDiscovery.discover(
                        home, Map.<String, String>of()::get, running("6.2.0-SNAPSHOT", "development", "3d683a35"));

        assertThat(Doctor.jars(found)).isEmpty();
    }

    @Test
    void jarsOfDifferentVersionsAreNotAConflict() throws Exception {
        jar(m2("6.0.0"), "6.0.0", "release", "db5b43c7", "2026-10-17T09:00:00Z");

        var found =
                JarDiscovery.discover(home, Map.<String, String>of()::get, running("6.1.0", "release", "aaaa1111"));

        assertThat(Doctor.jars(found)).isEmpty();
    }

    @Test
    void theEditorsBundledJarAndAWeaveJarVariableAreFound() throws Exception {
        Path bundled =
                jar(
                        home.resolve(".vscode/extensions/srijithunni7182.vscode-llm4j-loom-1.1.0/bin/weave.jar"),
                        "6.1.0", "release", "bbbb2222", "2026-10-20T00:00:00Z");
        Path chosen =
                jar(home.resolve("elsewhere/weave.jar"), "6.1.0", "development", "cccc3333", "2026-10-29T00:00:00Z");

        var found =
                JarDiscovery.discover(
                        home,
                        Map.of("WEAVE_JAR", chosen.toString())::get,
                        running("6.1.0", "release", "bbbb2222"));

        assertThat(found).extracting(JarDiscovery.Found::path).contains(bundled, chosen);
        assertThat(Doctor.jars(found).orElseThrow().reason()).contains("both say 6.1.0");
    }

    @Test
    void aJarThatCannotBeReadIsSkippedNotAnError() throws Exception {
        Files.createDirectories(m2("6.0.0").getParent());
        Files.writeString(m2("6.0.0"), "not a zip");

        var found =
                JarDiscovery.discover(home, Map.<String, String>of()::get, running("6.0.0", "release", "x"));

        assertThat(found).extracting(JarDiscovery.Found::path).doesNotContain(m2("6.0.0"));
    }
}
