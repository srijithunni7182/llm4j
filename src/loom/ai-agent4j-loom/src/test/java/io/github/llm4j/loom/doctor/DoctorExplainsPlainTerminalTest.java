package io.github.llm4j.loom.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F14: in a plain terminal there is no {@code weave}; the editor's PATH entry covers VS Code's own
 * terminals only. Doctor says so, and gives the install line, only when weave is really not there.
 */
class DoctorExplainsPlainTerminalTest {
    @TempDir Path dir;

    @Test
    void withNoWeaveOnThePathItExplainsWhatTheEditorCoversAndGivesTheInstallLine() throws Exception {
        Path empty = Files.createDirectories(dir.resolve("bin"));

        var check = Doctor.weaveOnPath(empty.toString(), ":", false);

        assertThat(check).isPresent();
        assertThat(check.get().status()).isEqualTo(Doctor.Status.WARN);
        assertThat(check.get().reason())
                .contains("not on this terminal's PATH")
                .contains("VS Code's own terminals");
        assertThat(check.get().action())
                .contains("jbang app install weave@srijithunni7182/llm4j")
                .contains("scripts/weave");
    }

    @Test
    void aWeaveOnThePathSaysNothing() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Files.writeString(bin.resolve("weave"), "#!/bin/sh\n");

        assertThat(Doctor.weaveOnPath(bin.toString(), ":", false)).isEmpty();
    }

    @Test
    void onWindowsAWeaveCmdOrExeCounts() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Files.writeString(bin.resolve("weave.cmd"), "@echo off\n");

        assertThat(Doctor.weaveOnPath(bin.toString(), ";", true)).isEmpty();
    }

    @Test
    void anEmptyOrMissingPathIsHandled() {
        assertThat(Doctor.weaveOnPath(null, ":", false)).isPresent();
        assertThat(Doctor.weaveOnPath("", ":", false)).isPresent();
    }
}
