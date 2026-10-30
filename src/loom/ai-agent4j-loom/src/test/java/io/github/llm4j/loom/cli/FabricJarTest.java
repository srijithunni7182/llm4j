package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 22 R10.1: the Fabric jar is found in the same order as the deployer skill's scripts. */
class FabricJarTest {

    @TempDir Path home;

    private Path jar(Path folder, String name, long seconds) throws Exception {
        Files.createDirectories(folder);
        Path file = Files.writeString(folder.resolve(name), "jar");
        Files.setLastModifiedTime(file, FileTime.fromMillis(seconds * 1000));
        return file;
    }

    private Optional<Path> find(Map<String, String> env, Path cwd) {
        return FabricJar.find(env::get, home, cwd);
    }

    @Test
    void theEnvironmentVariableWinsOverEverythingElse() throws Exception {
        Path given = jar(home.resolve("elsewhere"), "my-fabric.jar", 1);
        jar(home.resolve(".llm4j/tools/6.2.0"), "loom-fabric-6.2.0-all.jar", 9);

        assertThat(find(Map.of("FABRIC_JAR", given.toString()), home)).contains(given);
    }

    @Test
    void anEnvironmentVariableThatIsNotAFileIsAnErrorNotASilentFallback() {
        assertThatThrownBy(() -> find(Map.of("FABRIC_JAR", home.resolve("nope.jar").toString()), home))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FABRIC_JAR points at")
                .hasMessageContaining("not a file");
    }

    @Test
    void theNewestFetchedVersionWins() throws Exception {
        jar(home.resolve(".llm4j/tools/6.1.0"), "loom-fabric-6.1.0-all.jar", 1);
        Path newer = jar(home.resolve(".llm4j/tools/6.2.0"), "loom-fabric-6.2.0-all.jar", 5);

        assertThat(find(Map.of(), home)).contains(newer);
    }

    @Test
    void aBuildInTheRepositoryIsTheLastResort() throws Exception {
        Path repo = home.resolve("checkout");
        Path built = jar(repo.resolve("src/loom/loom-fabric/target"), "loom-fabric-6.2.0-all.jar", 1);

        assertThat(find(Map.of(), repo)).contains(built);
        jar(home.resolve(".llm4j/tools/6.2.0"), "loom-fabric-6.2.0-all.jar", 1);
        assertThat(find(Map.of(), repo)).get().isNotEqualTo(built);
    }

    @Test
    void sourcesAndJavadocJarsAreNotTheServer() throws Exception {
        jar(home.resolve(".llm4j/tools/6.2.0"), "loom-fabric-6.2.0-sources-all.jar", 5);

        assertThat(find(Map.of(), home)).isEmpty();
    }

    @Test
    void nothingFoundIsEmpty() {
        assertThat(find(Map.of(), home)).isEmpty();
    }
}
