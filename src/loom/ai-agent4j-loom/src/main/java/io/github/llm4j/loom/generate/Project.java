package io.github.llm4j.loom.generate;

import io.github.llm4j.loom.init.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * An existing project as the generator sees it: the files a workflow project holds, by the same
 * names whichever layout it has ({@code main.loom}, {@code prompts/x.md}, {@code
 * tests/x.test.yaml}), and where each one lives on disk. Anything else in the folder (keys, build
 * output, source) is not part of it and is never read or written.
 */
public final class Project {

    private final Path root;
    private final Writer.Layout layout;
    private final Map<String, String> files;
    private final Map<String, String> readOnly;
    private final String scriptRel;

    private Project(
            Path root,
            Writer.Layout layout,
            Map<String, String> files,
            Map<String, String> readOnly,
            String scriptRel) {
        this.root = root;
        this.layout = layout;
        this.files = files;
        this.readOnly = readOnly;
        this.scriptRel = scriptRel;
    }

    /**
     * Scripts and tool files beside the script that it imports: copied into a trial folder, never
     * changed.
     */
    public Map<String, String> readOnly() {
        return readOnly;
    }

    /** Where a logical name is relative to the project's root. */
    public String relOf(String logical) {
        return logical.equals("main.loom") ? scriptRel : Writer.placed(layout, logical);
    }

    public Path root() {
        return root;
    }

    public Writer.Layout layout() {
        return layout;
    }

    /** The files by logical name (the flat names). */
    public Map<String, String> files() {
        return files;
    }

    /** Where a logical name is (or would be) on disk. */
    public Path place(String logical) {
        return root.resolve(relOf(logical)).normalize();
    }

    private static final long MAX_BYTES = 1024 * 1024;

    /** Reads the project that holds {@code script}. */
    public static Project read(Path script) throws IOException {
        Path abs = script.toAbsolutePath().normalize();
        Path root = ProjectLayout.root(abs);
        Writer.Layout layout =
                abs.endsWith(Path.of("src/main/resources").resolve(abs.getFileName()))
                                && Files.isRegularFile(root.resolve("pom.xml"))
                        ? Writer.Layout.MAVEN
                        : Writer.Layout.FLAT;
        Map<String, String> files = new TreeMap<>();
        Map<String, String> readOnly = new TreeMap<>();
        String scriptRel = root.relativize(abs).toString().replace('\\', '/');
        Path scriptDir = abs.getParent();
        try (Stream<Path> walk = Files.walk(root, 8)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                Path real = p.toRealPath();
                if (!real.startsWith(root.toRealPath())) continue; // a link out of the folder
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.equals(scriptRel)) {
                    files.put("main.loom", Files.readString(p, StandardCharsets.UTF_8));
                    continue;
                }
                if (p.getParent().equals(scriptDir)
                        && (rel.endsWith(".loom") || rel.endsWith(".loot"))
                        && !rel.endsWith("tools.loot")
                        && Files.size(p) <= MAX_BYTES) {
                    readOnly.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                    continue;
                }
                String logical = logicalOf(layout, rel);
                if (logical == null
                        || logical.equals("main.loom")
                        || Writer.problemWith(logical) != null) continue;
                if (Files.size(p) > MAX_BYTES) continue;
                files.put(logical, Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        // the script is main.loom to the generator whatever it is called on disk
        return new Project(root, layout, files, readOnly, scriptRel);
    }

    private static final List<Pattern> MAVEN_MAIN =
            List.of(
                    Pattern.compile(
                            "src/main/resources/(main\\.loom|tools\\.loot|review\\.properties|prompts/.+)"));

    static String logicalOf(Writer.Layout layout, String rel) {
        if (layout == Writer.Layout.FLAT) return rel;
        for (Pattern p : MAVEN_MAIN) {
            Matcher m = p.matcher(rel);
            if (m.matches()) return m.group(1);
        }
        Matcher t = Pattern.compile("src/test/resources/loom-(tests/.+)").matcher(rel);
        if (t.matches()) return t.group(1);
        Matcher e = Pattern.compile("src/test/resources/(eval/.+)").matcher(rel);
        if (e.matches()) return e.group(1);
        if (rel.equals("README.md") || rel.equals("PLAN.md") || rel.equals("plan.json")) return rel;
        return null;
    }

    /** The project with changed files; the on-disk project is not touched. */
    public Map<String, String> with(Map<String, String> changed) {
        Map<String, String> out = new LinkedHashMap<>(files);
        out.putAll(changed);
        return out;
    }
}
