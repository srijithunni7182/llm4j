package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F16: an editor's Java language server leaves "Unresolved compilation problem" stubs in {@code
 * target/classes}, and a plain {@code mvn package} packages them. {@code scripts/CheckJars.java},
 * which the release check runs, refuses such a jar, and a jar that does not say what it is.
 * Fragile: it starts a JVM to run the script.
 */
@Tag("fragile")
class ReleaseCheckRejectsEclipseStubsTest {
    private static final Path ROOT = Path.of("../../../").toAbsolutePath().normalize();

    @TempDir Path dir;

    private Path jar(String name, boolean identity, String classBody) throws Exception {
        Path target = Files.createDirectories(dir.resolve("mod/target"));
        Path jar = target.resolve(name);
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (identity) {
            m.getMainAttributes().putValue("Implementation-Version", "6.2.0-SNAPSHOT");
            m.getMainAttributes().putValue("Build-Kind", "development");
            m.getMainAttributes().putValue("Build-Commit", "3d683a35");
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), m)) {
            out.putNextEntry(new JarEntry("a/B.class"));
            out.write(classBody.getBytes(StandardCharsets.ISO_8859_1));
            out.closeEntry();
        }
        return jar;
    }

    private record Ran(int exit, String out) {}

    private Ran check() throws Exception {
        Process p =
                new ProcessBuilder(
                                "java",
                                ROOT.resolve("scripts/CheckJars.java").toString(),
                                dir.toString())
                        .redirectErrorStream(true)
                        .start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        return new Ran(p.waitFor(), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aJarWithACompilerStubClassIsRefusedAndTheClassIsNamed() throws Exception {
        jar("lib-1.0.jar", true, "xx Unresolved compilation problem: yy");

        Ran ran = check();

        assertThat(ran.exit()).isEqualTo(1);
        assertThat(ran.out()).contains("a/B.class is a compiler stub").contains("mvn clean package");
    }

    @Test
    void aJarThatDoesNotSayWhatItIsIsRefused() throws Exception {
        jar("lib-1.0.jar", false, "fine");

        Ran ran = check();

        assertThat(ran.exit()).isEqualTo(1);
        assertThat(ran.out()).contains("no usable Build-Commit");
    }

    @Test
    void aCleanJarPassesAndSourcesJarsAreLeftAlone() throws Exception {
        jar("lib-1.0.jar", true, "fine");
        jar("lib-1.0-sources.jar", false, "Unresolved compilation problem");

        Ran ran = check();

        assertThat(ran.exit()).isZero();
        assertThat(ran.out()).contains("checked 1 jar(s)");
    }
}
