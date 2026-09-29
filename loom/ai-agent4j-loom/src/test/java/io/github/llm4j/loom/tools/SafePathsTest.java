package io.github.llm4j.loom.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafePathsTest {

    @TempDir
    Path root;

    @Test
    void pathsInsideAreResolved() throws Exception {
        Path base = Files.createDirectories(root.resolve("script"));
        assertThat(SafePaths.inside(base, "audio/a.wav")).isEqualTo(base.resolve("audio/a.wav"));
        assertThat(SafePaths.inside(base, "./x/../y.wav")).isEqualTo(base.resolve("y.wav"));
        assertThat(SafePaths.inside(base, base.resolve("z.wav").toString())).isEqualTo(base.resolve("z.wav"));
    }

    @Test
    void escapesAreRefused() throws Exception {
        Path base = Files.createDirectories(root.resolve("script"));
        assertThatThrownBy(() -> SafePaths.inside(base, "../secret")).hasMessageContaining("outside the script's directory");
        assertThatThrownBy(() -> SafePaths.inside(base, "/etc/passwd")).hasMessageContaining("outside");
        assertThatThrownBy(() -> SafePaths.inside(base, " ")).hasMessageContaining("no path given");
        assertThatThrownBy(() -> SafePaths.inside(base, null)).hasMessageContaining("no path given");
    }

    @Test
    void symbolicLinksLeadingOutAreRefused() throws Exception {
        Path base = Files.createDirectories(root.resolve("script"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.writeString(outside.resolve("secret.wav"), "x");
        Files.createSymbolicLink(base.resolve("link"), outside);
        assertThatThrownBy(() -> SafePaths.inside(base, "link/secret.wav")).hasMessageContaining("leads outside");
        assertThatThrownBy(() -> SafePaths.inside(base, "link/new/file.wav")).hasMessageContaining("leads outside");
    }
}
