package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.BuildIdentity;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

/** {@code weave --version} tells a release from a development build and names the commit. */
class VersionLineTest {
    private static BuildIdentity identity(String kind, String commit) {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().putValue("Implementation-Version", "6.2.0-SNAPSHOT");
        m.getMainAttributes().putValue("Build-Kind", kind);
        m.getMainAttributes().putValue("Build-Commit", commit);
        m.getMainAttributes().putValue("Build-Time", "2026-10-29T15:30:00Z");
        return BuildIdentity.of(m);
    }

    @Test
    void aDevelopmentBuildNamesItsCommitAndKeepsTheProtocolSuffixTheEditorReads() {
        String line = WeaveCLI.BuildVersion.line(identity("development", "3d683a35"));

        assertThat(line)
                .isEqualTo(
                        "weave 6.2.0-SNAPSHOT (development, 3d683a35, built 2026-10-29T15:30:00Z),"
                                + " serve protocol "
                                + io.github.llm4j.loom.serve.Server.PROTOCOL);
        // the editor's handshake still finds the protocol
        assertThat(line).containsPattern("serve protocol \\d+$");
    }

    @Test
    void aReleaseSaysRelease() {
        assertThat(WeaveCLI.BuildVersion.line(identity("release", "db5b43c7")))
                .contains("(release, db5b43c7");
    }

    @Test
    void classesRunFromADirectoryAreNotPassedOffAsABuild() {
        assertThat(
                        WeaveCLI.BuildVersion.line(
                                BuildIdentity.ofClass(VersionLineTest.class)))
                .startsWith("weave (run from classes");
    }
}
