package io.github.llm4j.loom.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The deployer script test runs the real scripts against jars found in the {@code target} folders,
 * which can be left over from different commits. {@link DeployerJars} says so before a script is run,
 * so the test skips with the reason instead of failing halfway through.
 */
class DeployerJarsTest {

    @TempDir Path dir;

    private Path jar(String name, String commit) throws IOException {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().putValue("Implementation-Version", "6.2.0");
        m.getMainAttributes().putValue("Build-Kind", "development");
        if (commit != null) m.getMainAttributes().putValue("Build-Commit", commit);
        Path path = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(path);
                JarOutputStream jar = new JarOutputStream(out, m)) {
            // a manifest is all a comparison reads
        }
        return path;
    }

    @Test
    void jarsFromOneCommitAgree() throws Exception {
        Map<String, Path> jars =
                Map.of(
                        "WEAVE_JAR", jar("weave.jar", "f9fc50ad"),
                        "INSPECT_JAR", jar("inspect.jar", "f9fc50ad"),
                        "INSPECT_CORE_JAR", jar("core.jar", "f9fc50ad"),
                        "FABRIC_JAR", jar("fabric.jar", "f9fc50ad"));

        assertThat(DeployerJars.mismatch(jars)).isEmpty();
    }

    @Test
    void anAddOnBuiltFromAnotherCommitIsNamedWithBothBuilds() throws Exception {
        Map<String, Path> jars =
                Map.of(
                        "WEAVE_JAR", jar("weave.jar", "f9fc50ad"),
                        "INSPECT_JAR", jar("inspect.jar", "ee7db1d2"),
                        "INSPECT_CORE_JAR", jar("core.jar", "f9fc50ad"),
                        "FABRIC_JAR", jar("fabric.jar", "f9fc50ad"));

        assertThat(DeployerJars.mismatch(jars))
                .hasValueSatisfying(
                        sentence ->
                                assertThat(sentence)
                                        .contains("ee7db1d2")
                                        .contains("f9fc50ad")
                                        .contains("mvn -DskipTests clean package"));
    }

    @Test
    void everyAddOnIsComparedNotOnlyTheFirst() throws Exception {
        Map<String, Path> jars =
                Map.of(
                        "WEAVE_JAR", jar("weave.jar", "f9fc50ad"),
                        "INSPECT_JAR", jar("inspect.jar", "f9fc50ad"),
                        "INSPECT_CORE_JAR", jar("core.jar", "11111111"),
                        "FABRIC_JAR", jar("fabric.jar", "f9fc50ad"));

        assertThat(DeployerJars.mismatch(jars)).hasValueSatisfying(s -> assertThat(s).contains("11111111"));
    }

    @Test
    void aJarWithoutABuildCommitCannotDisagree() throws Exception {
        Map<String, Path> jars =
                Map.of(
                        "WEAVE_JAR", jar("weave.jar", "f9fc50ad"),
                        "INSPECT_JAR", jar("inspect.jar", "f9fc50ad"),
                        "INSPECT_CORE_JAR", jar("core.jar", "f9fc50ad"),
                        "FABRIC_JAR", jar("fabric.jar", null));

        assertThat(DeployerJars.mismatch(jars)).isEmpty();
    }
}
