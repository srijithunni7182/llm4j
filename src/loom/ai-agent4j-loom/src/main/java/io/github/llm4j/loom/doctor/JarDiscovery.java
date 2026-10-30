package io.github.llm4j.loom.doctor;

import io.github.llm4j.BuildIdentity;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.jar.JarFile;

/**
 * Finds the Loom jars a developer might be running without knowing it: the one that is running, the
 * copies in the local Maven repository, the jar the editor extension bundles, and the one named by
 * {@code WEAVE_JAR}. Reads local files only, and skips a jar it cannot read.
 */
public final class JarDiscovery {

    /** A jar that was found, where, and what it says it is. {@code path} is null for the running one. */
    public record Found(String where, Path path, BuildIdentity identity) {
        String label() {
            return path == null ? where : path.toString();
        }
    }

    private static final String LOOM = "ai-agent4j-loom";

    private JarDiscovery() {}

    /**
     * @param home the user's home folder (where {@code .m2} and the editor's extensions live)
     * @param env the environment, for {@code WEAVE_JAR}
     * @param running the weave that is running
     */
    public static List<Found> discover(Path home, Function<String, String> env, Found running) {
        List<Found> found = new ArrayList<>();
        found.add(running);
        Path m2 = home.resolve(".m2/repository/io/github/srijithunni7182/" + LOOM);
        if (Files.isDirectory(m2)) {
            try (DirectoryStream<Path> versions = Files.newDirectoryStream(m2)) {
                for (Path version : versions)
                    for (String suffix : List.of("", "-cli"))
                        add(
                                found,
                                "the local Maven repository",
                                version.resolve(LOOM + "-" + version.getFileName() + suffix + ".jar"));
            } catch (IOException e) {
                // an unreadable repository is not a finding
            }
        }
        for (String editor : List.of(".vscode", ".vscode-server", ".vscode-insiders", ".cursor")) {
            Path extensions = home.resolve(editor).resolve("extensions");
            if (!Files.isDirectory(extensions)) continue;
            try (DirectoryStream<Path> dirs =
                    Files.newDirectoryStream(extensions, "srijithunni7182.vscode-llm4j-loom-*")) {
                for (Path dir : dirs)
                    add(found, "the editor extension's bundled jar", dir.resolve("bin/weave.jar"));
            } catch (IOException e) {
                // as above
            }
        }
        String named = env.apply("WEAVE_JAR");
        if (named != null && !named.isBlank()) add(found, "WEAVE_JAR", Path.of(named.strip()));
        return found;
    }

    private static void add(List<Found> found, String where, Path jar) {
        if (!Files.isRegularFile(jar)) return;
        try (JarFile file = new JarFile(jar.toFile())) {
            if (file.getManifest() == null) return;
            BuildIdentity id = BuildIdentity.of(file.getManifest());
            // the running jar may be the same file; list each path once
            if (found.stream().anyMatch(f -> jar.equals(f.path()))) return;
            found.add(new Found(where, jar, id));
        } catch (IOException | RuntimeException e) {
            // not a readable jar: nothing to compare
        }
    }
}
