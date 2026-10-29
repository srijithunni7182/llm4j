import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Checks the jars a build produced, for the release check and the extension packaging:
 *
 * <ul>
 *   <li>every jar says what it is: {@code Implementation-Version}, {@code Build-Kind}, {@code
 *       Build-Commit} are in its manifest and none is an unexpanded placeholder;
 *   <li>no class in it is a compiler stub. When an editor's Java language server writes into
 *       {@code target/classes}, a file that did not compile becomes a class that throws "Unresolved
 *       compilation problem" when it is loaded, and a plain {@code mvn package} then ships it.
 * </ul>
 *
 * Usage: {@code java scripts/CheckJars.java <folder or jar>...}. Looks in each folder for {@code
 * *.jar} files (not {@code -sources}, {@code -javadoc} or {@code -tests}), recursively under {@code
 * target} folders. Exits 1 and names each problem; prints nothing but a count when all is well.
 */
public final class CheckJars {
    private static final String STUB_MARKER = "Unresolved compilation problem";
    private static final List<String> IDENTITY = List.of("Implementation-Version", "Build-Kind", "Build-Commit");

    public static void main(String[] args) throws IOException {
        List<Path> jars = new ArrayList<>();
        for (String arg : args) collect(Path.of(arg), jars);
        List<String> problems = new ArrayList<>();
        for (Path jar : jars) problems.addAll(check(jar));
        if (!problems.isEmpty()) {
            problems.forEach(p -> System.err.println("FAIL " + p));
            System.exit(1);
        }
        System.out.println("checked " + jars.size() + " jar(s): identity present, no compiler stubs");
    }

    static void collect(Path path, List<Path> out) throws IOException {
        if (Files.isRegularFile(path)) {
            out.add(path);
            return;
        }
        if (!Files.isDirectory(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> p.toString().contains("target"))
                    .filter(p -> !p.getFileName().toString().matches(".*-(sources|javadoc|tests)\\.jar"))
                    .sorted()
                    .forEach(out::add);
        }
    }

    static List<String> check(Path path) throws IOException {
        List<String> problems = new ArrayList<>();
        try (JarFile jar = new JarFile(path.toFile())) {
            var manifest = jar.getManifest();
            for (String name : IDENTITY) {
                String v = manifest == null ? null : manifest.getMainAttributes().getValue(name);
                if (v == null || v.isBlank() || v.startsWith("${"))
                    problems.add(path + ": manifest has no usable " + name);
            }
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    String text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                    if (text.contains(STUB_MARKER))
                        problems.add(path + ": " + entry.getName() + " is a compiler stub (\"" + STUB_MARKER + "\"); rebuild with mvn clean package");
                }
            }
        }
        return problems;
    }
}
