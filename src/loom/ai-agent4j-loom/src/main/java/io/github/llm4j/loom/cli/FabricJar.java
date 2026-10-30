package io.github.llm4j.loom.cli;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Where the Fabric server jar is. The order is the deployer skill's, so the scripts and {@code
 * weave fabric} agree: the {@code FABRIC_JAR} variable, then what {@code weave tools fetch} put in
 * {@code ~/.llm4j/tools/<version>/} (the newest), then a build in the repository the command is run
 * from. {@code weave} only ever launches the jar by path; it has no class dependency on it.
 */
final class FabricJar {

    static final String VARIABLE = "FABRIC_JAR";

    private static final String BUILD_FOLDER = "src/loom/loom-fabric/target";

    private FabricJar() {}

    /**
     * @param env the environment, for {@code FABRIC_JAR}
     * @param home the user's home folder
     * @param cwd where the command was run, for a build in a checkout
     * @throws IllegalArgumentException when {@code FABRIC_JAR} names something that is not a file:
     *     a wrong variable is an error, not a reason to use a different jar
     */
    static Optional<Path> find(Function<String, String> env, Path home, Path cwd) {
        String given = env.apply(VARIABLE);
        if (given != null && !given.isBlank()) {
            Path path = Path.of(given);
            if (!Files.isRegularFile(path))
                throw new IllegalArgumentException(
                        VARIABLE + " points at '" + given + "', which is not a file");
            return Optional.of(path);
        }
        Optional<Path> fetched = newestIn(subfolders(home.resolve(".llm4j/tools")));
        if (fetched.isPresent()) return fetched;
        return newestIn(Stream.of(cwd.resolve(BUILD_FOLDER)));
    }

    private static Stream<Path> subfolders(Path tools) {
        if (!Files.isDirectory(tools)) return Stream.empty();
        try (DirectoryStream<Path> versions = Files.newDirectoryStream(tools, Files::isDirectory)) {
            java.util.List<Path> found = new java.util.ArrayList<>();
            versions.forEach(found::add);
            return found.stream();
        } catch (IOException e) {
            return Stream.empty(); // an unreadable folder is not a jar
        }
    }

    private static Optional<Path> newestIn(Stream<Path> folders) {
        return folders.flatMap(FabricJar::jarsIn)
                .max(Comparator.comparing(FabricJar::modified));
    }

    private static Stream<Path> jarsIn(Path folder) {
        if (!Files.isDirectory(folder)) return Stream.empty();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(folder, FabricJar::isServer)) {
            java.util.List<Path> found = new java.util.ArrayList<>();
            jars.forEach(found::add);
            return found.stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private static boolean isServer(Path file) {
        String name = file.getFileName().toString();
        boolean named =
                name.equals("fabric.jar")
                        || (name.startsWith("loom-fabric-") && name.endsWith("-all.jar"));
        return named
                && !name.contains("sources")
                && !name.contains("javadoc")
                && !name.contains("tests");
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
