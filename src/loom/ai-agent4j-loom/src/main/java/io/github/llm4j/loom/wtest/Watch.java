package io.github.llm4j.loom.wtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What {@code --watch} looks at: the script, the files it imports, its prompts and the test files,
 * with the time each was last changed, and which of them changed between two looks. A change to a
 * test file reruns that file's cases; a change to anything the script is built from reruns them
 * all.
 */
public final class Watch {

    private Watch() {}

    /** The files a run depends on. */
    public static List<Path> watched(Path script, List<Path> testPaths, TestRunner.Suite suite) {
        Set<Path> out = new LinkedHashSet<>();
        out.add(script.toAbsolutePath().normalize());
        for (String imported : suite.loaded().getImports()) {
            Path p = script.toAbsolutePath().getParent().resolve(imported).normalize();
            out.add(p);
        }
        Path prompts = script.toAbsolutePath().getParent().resolve("prompts");
        if (Files.isDirectory(prompts))
            try (Stream<Path> s = Files.walk(prompts)) {
                s.filter(Files::isRegularFile)
                        .forEach(f -> out.add(f.toAbsolutePath().normalize()));
            } catch (IOException e) {
                // an unreadable prompt folder is seen as unchanged
            }
        for (TestFile f : suite.files()) out.add(f.path().toAbsolutePath().normalize());
        for (Path t : testPaths)
            if (Files.isDirectory(t))
                try (Stream<Path> s = Files.list(t)) {
                    s.filter(f -> f.getFileName().toString().endsWith(".test.yaml"))
                            .forEach(f -> out.add(f.toAbsolutePath().normalize()));
                } catch (IOException e) {
                    // seen as unchanged
                }
        return List.copyOf(out);
    }

    /** The last change time of each file (-1 for one that is gone). */
    public static Map<Path, Long> snapshot(List<Path> files) {
        Map<Path, Long> out = new LinkedHashMap<>();
        for (Path f : files) {
            long t;
            try {
                t = Files.getLastModifiedTime(f).toMillis();
            } catch (IOException e) {
                t = -1;
            }
            out.put(f, t);
        }
        return out;
    }

    /** The files that are new, gone or changed between two looks. */
    public static Set<Path> changed(Map<Path, Long> before, Map<Path, Long> after) {
        Set<Path> out = new LinkedHashSet<>();
        for (var e : after.entrySet())
            if (!e.getValue().equals(before.get(e.getKey()))) out.add(e.getKey());
        for (Path p : before.keySet()) if (!after.containsKey(p)) out.add(p);
        return out;
    }

    /**
     * The test files whose cases need to run again, or empty when every case does (the script, an
     * import or a prompt changed).
     */
    public static Set<Path> onlyTestFiles(Set<Path> changed, List<TestFile> files) {
        Set<Path> tests = new LinkedHashSet<>();
        for (TestFile f : files) tests.add(f.path().toAbsolutePath().normalize());
        Set<Path> only = new LinkedHashSet<>();
        for (Path p : changed) {
            if (tests.contains(p) || p.getFileName().toString().endsWith(".test.yaml")) only.add(p);
            else return Set.of();
        }
        return only;
    }
}
