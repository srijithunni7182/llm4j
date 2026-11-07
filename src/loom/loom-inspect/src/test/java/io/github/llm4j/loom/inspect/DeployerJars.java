package io.github.llm4j.loom.inspect;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Whether the jars the deployer script test runs against are from one build. They are found in the
 * {@code target} folders, so after a partial rebuild they can come from different commits, and the
 * runtime refuses to start them together ({@code CoreMatch}); the test would then fail halfway through
 * a script instead of saying what to rebuild.
 */
final class DeployerJars {

    private static final String REBUILD =
            " Rebuild them together: mvn -DskipTests clean package (or skip this test).";

    private DeployerJars() {}

    /**
     * The sentence to skip with, or empty when every add-on jar agrees with the weave jar (or has no
     * build information to disagree with).
     */
    static Optional<String> mismatch(Map<String, Path> jars) throws IOException {
        Path weave = jars.get("WEAVE_JAR");
        Manifest core = manifest(weave);
        for (String addOn : List.of("INSPECT_JAR", "INSPECT_CORE_JAR", "FABRIC_JAR")) {
            Path path = jars.get(addOn);
            if (path == null) continue;
            Optional<String> sentence =
                    CoreMatch.mismatch(
                            path.getFileName().toString(), manifest(path), weave.getFileName().toString(), core);
            if (sentence.isPresent()) return Optional.of(sentence.get() + REBUILD);
        }
        return Optional.empty();
    }

    private static Manifest manifest(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest m = file.getManifest();
            return m == null ? new Manifest() : m;
        }
    }
}
