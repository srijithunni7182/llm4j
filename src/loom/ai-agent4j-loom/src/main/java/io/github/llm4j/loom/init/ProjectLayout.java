package io.github.llm4j.loom.init;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the pieces of a workflow project are, for either layout {@code weave init} makes. A flat folder keeps the script, {@code eval/golden}, the README
 * and {@code .env} together. A Maven project (a {@code pom.xml} above the script) keeps the script in {@code src/main/resources}, the dataset in
 * {@code src/test/resources/eval/golden}, and the README and {@code .env} at the project's root. Commands look in the same places a person would.
 */
public final class ProjectLayout {

    private ProjectLayout() {}

    /** The nearest folder at or above the script that holds a {@code pom.xml}; the script's own folder when there is none. */
    public static Path root(Path script) {
        Path dir = script.toAbsolutePath().normalize().getParent();
        for (Path d = dir; d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("pom.xml"))) return d;
        }
        return dir;
    }

    /**
     * The golden dataset folder for a script: {@code eval/golden} beside it when that exists, else {@code src/test/resources/eval/golden} of its Maven
     * project when that exists or the script is under {@code src/main/resources} (so {@code --init} puts a new one there), else {@code eval/golden} beside it.
     */
    public static Path dataset(Path script) {
        Path dir = script.toAbsolutePath().normalize().getParent();
        Path beside = dir.resolve("eval").resolve("golden");
        if (Files.isDirectory(beside)) return beside;
        Path root = root(script);
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            Path maven = root.resolve("src/test/resources/eval/golden");
            if (Files.isDirectory(maven) || dir.startsWith(root.resolve("src/main/resources"))) return maven;
        }
        return beside;
    }

    /** The script as a person types it from the project's root: {@code src/main/resources/main.loom} in a Maven project, else just the file name. */
    public static String shown(Path script) {
        Path abs = script.toAbsolutePath().normalize();
        Path root = root(script);
        return root.equals(abs.getParent()) ? abs.getFileName().toString() : root.relativize(abs).toString().replace('\\', '/');
    }

    /** Where the script's {@code .env} is looked for: beside the script when one is there, else at the project's root. */
    public static Path envFile(Path script) {
        Path dir = script.toAbsolutePath().normalize().getParent();
        Path beside = dir.resolve(".env");
        return Files.isRegularFile(beside) ? beside : root(script).resolve(".env");
    }

    /** The script of a project folder: {@code main.loom} there, else {@code src/main/resources/main.loom}; null when neither is there. */
    public static Path script(Path folder) {
        Path flat = folder.resolve("main.loom");
        if (Files.isRegularFile(flat)) return flat;
        Path maven = folder.resolve("src/main/resources/main.loom");
        return Files.isRegularFile(maven) ? maven : null;
    }
}
