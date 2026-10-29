package io.github.llm4j.loom.install;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The end of {@code weave guide --install-skill} for the deployer skill: what the skill's scripts
 * will find and what they will not, in the order the scripts look (the variable, the skill's {@code
 * bin/}, the folder {@code weave tools fetch} fills). It never says the skill is ready when a script
 * it ships would stop for a missing jar.
 */
public final class InstallSummary {
    private InstallSummary() {}

    /**
     * @param home the user's home folder
     * @param env the environment
     * @param skillFolder the installed skill folder (its {@code bin/} is looked in)
     * @param runningWeave how the running weave describes itself
     */
    public static List<String> lines(
            Path home, Function<String, String> env, Path skillFolder, String runningWeave) {
        List<String> out = new ArrayList<>();
        out.add("What the deployer skill's scripts will find:");
        out.add("  weave   found: this weave, " + runningWeave);
        Optional<String> fabric = find(home, env, skillFolder, "FABRIC_JAR", "fabric.jar", "loom-fabric-", "-all.jar");
        Optional<String> inspect = find(home, env, skillFolder, "INSPECT_JAR", "loom-inspect.jar", "loom-inspect-", ".jar", "loom-inspect-core-");
        Optional<String> core = find(home, env, skillFolder, "INSPECT_CORE_JAR", "loom-inspect-core.jar", "loom-inspect-core-", ".jar");
        out.add(
                "  Fabric  "
                        + fabric.map(p -> "found: " + p)
                                .orElse("missing: run `weave tools fetch fabric`"));
        boolean inspectOk = inspect.isPresent() && core.isPresent();
        out.add(
                "  inspect "
                        + (inspectOk
                                ? "found: " + inspect.get() + " and " + core.get()
                                : "missing: run `weave tools fetch inspect`"));
        if (fabric.isPresent() && inspectOk) out.add("All the jars the scripts launch were found: they are ready to run.");
        else
            out.add(
                    "Until the missing jars are fetched, the scripts that launch them will stop with a message"
                            + " (fabric-up, run-observed). `weave tools fetch all` asks first and checks checksums;"
                            + " or set FABRIC_JAR, INSPECT_JAR and INSPECT_CORE_JAR.");
        return out;
    }

    /** Looks where lib.sh looks (after the editor's jar, which is for weave only). */
    private static Optional<String> find(
            Path home,
            Function<String, String> env,
            Path skill,
            String variable,
            String exactName,
            String prefix,
            String suffix,
            String... excludedPrefix) {
        String given = env.apply(variable);
        if (given != null && !given.isBlank() && Files.isRegularFile(Path.of(given.strip())))
            return Optional.of(variable + "=" + given.strip());
        List<Path> dirs = new ArrayList<>();
        dirs.add(skill.resolve("bin"));
        Path tools = home.resolve(".llm4j/tools");
        if (Files.isDirectory(tools)) {
            try (Stream<Path> versions = Files.list(tools)) {
                versions.filter(Files::isDirectory)
                        .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                        .forEach(dirs::add);
            } catch (IOException e) {
                // unreadable: nothing found there
            }
        }
        for (Path dir : dirs) {
            Path exact = dir.resolve(exactName);
            if (Files.isRegularFile(exact)) return Optional.of(exact.toString());
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, prefix + "*" + suffix)) {
                for (Path f : files) {
                    String name = f.getFileName().toString();
                    boolean excluded = false;
                    for (String x : excludedPrefix) excluded |= name.startsWith(x);
                    if (!excluded && !name.matches(".*(sources|javadoc|tests).*")) return Optional.of(f.toString());
                }
            } catch (IOException e) {
                // unreadable: nothing found there
            }
        }
        return Optional.empty();
    }
}
