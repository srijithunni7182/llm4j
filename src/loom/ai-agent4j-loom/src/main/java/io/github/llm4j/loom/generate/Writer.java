package io.github.llm4j.loom.generate;

import io.github.llm4j.loom.init.TemplateWriter;
import io.github.llm4j.loom.init.Templates;
import io.github.llm4j.loom.review.fact.SecretShapes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Renders the files a model wrote into a temporary project: checks each path and size, refuses
 * anything shaped like a key, stamps a header (generator version, date, plan fingerprint; never a
 * model name) and lays the files out flat or as a Maven project.
 */
public final class Writer {

    private Writer() {}

    public enum Layout {
        MAVEN,
        FLAT
    }

    /**
     * @param generator for example {@code weave generate 6.1.0}; @param date the day, as text
     */
    public record Stamp(String generator, String date) {}

    public static final long MAX_FILE_BYTES = 1024 * 1024;

    private static final List<Pattern> ALLOWED =
            List.of(
                    Pattern.compile("main\\.loom"),
                    Pattern.compile("tools\\.loot"),
                    Pattern.compile("review\\.properties"),
                    Pattern.compile("README\\.md"),
                    Pattern.compile("PLAN\\.md"),
                    Pattern.compile("plan\\.json"),
                    Pattern.compile("prompts/[a-z0-9][a-z0-9_-]*\\.md"),
                    Pattern.compile("tests/[a-z0-9][a-z0-9_.-]*\\.test\\.yaml"),
                    Pattern.compile("eval/golden/[a-z0-9][a-z0-9_.-]*\\.yaml"));

    /** Why a path may not be written, or null when it may. */
    public static String problemWith(String path) {
        if (path == null || path.isBlank()) return "a file with no name";
        if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*"))
            return path + " is an absolute path";
        if (path.contains("..") || path.contains("\\") || path.contains("\0"))
            return path + " leaves the project folder";
        for (Pattern p : ALLOWED) if (p.matcher(path).matches()) return null;
        return path
                + " is not a file a workflow project holds (main.loom, tools.loot, prompts/*.md, tests/*.test.yaml, eval/golden/*.yaml, README.md, review.properties)";
    }

    /** Where a file goes in a layout. */
    public static String placed(Layout layout, String file) {
        if (layout == Layout.FLAT) return file;
        if (file.equals("tools.loot") || file.equals("review.properties"))
            return "src/main/resources/" + file;
        return Templates.mavenLayout(file);
    }

    /**
     * Problems with the files themselves, before anything is written: unsafe paths, files that are
     * too big, key-shaped text. Empty when all is well.
     */
    public static List<String> problems(Map<String, String> files) {
        List<String> out = new ArrayList<>();
        for (var e : files.entrySet()) {
            String bad = problemWith(e.getKey());
            if (bad != null) {
                out.add(bad);
                continue;
            }
            if (e.getValue() == null) out.add(e.getKey() + " has no content");
            else {
                if (e.getValue().getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES)
                    out.add(e.getKey() + " is larger than " + MAX_FILE_BYTES / 1024 + " KB");
                if (SecretShapes.in(e.getValue()))
                    out.add(
                            e.getKey()
                                    + " holds something shaped like a key or password; keys are read from env.NAME, never written");
            }
        }
        return out;
    }

    /**
     * The files of a project in the temporary workspace; throws when {@link #problems} is not
     * empty.
     */
    public static Workspace render(
            Map<String, String> files, Plan plan, Layout layout, Stamp stamp, Path tmpRoot)
            throws IOException {
        List<String> bad = problems(files);
        if (!bad.isEmpty()) throw new IllegalArgumentException(String.join("; ", bad));
        Path dir = Files.createTempDirectory(tmpRoot, "weave-generate-");
        String hash = PlanJson.hash(plan);
        List<String> written = new ArrayList<>();
        try {
            for (var e : new TreeMap<>(files).entrySet()) {
                String to = placed(layout, e.getKey());
                Path p = dir.resolve(to);
                Files.createDirectories(p.getParent());
                String body = e.getValue();
                if (e.getKey().startsWith("tests/") && e.getKey().endsWith(".test.yaml"))
                    body = pointedAtScript(body, p, dir.resolve(placed(layout, "main.loom")));
                Files.writeString(
                        p, stamped(e.getKey(), body, stamp, hash), StandardCharsets.UTF_8);
                written.add(to);
            }
            if (layout == Layout.MAVEN && files.containsKey("eval/golden/dataset.yaml")) {
                Map<String, String> vars =
                        Map.of("name", plan.name(), "artifact", slug(plan.name()));
                var parts =
                        List.of(
                                new TemplateWriter.Part(
                                        Templates.JAVA_TESTS, Templates.JAVA_TESTS_FILES));
                TemplateWriter.Result r = TemplateWriter.write(parts, dir, vars);
                written.addAll(r.created());
            }
            written.addAll(extras(dir, layout, files));
        } catch (IOException | RuntimeException e) {
            new Workspace(dir, written).close();
            throw e;
        }
        return new Workspace(dir, written);
    }

    /**
     * A test file names its script relative to itself; where that is depends on the layout, so the
     * generator sets it.
     */
    static String pointedAtScript(String yaml, Path testFile, Path script) {
        String relative = testFile.getParent().relativize(script).toString().replace('\\', '/');
        if (Pattern.compile("(?m)^script:.*$").matcher(yaml).find())
            return yaml.replaceFirst(
                    "(?m)^script:.*$",
                    java.util.regex.Matcher.quoteReplacement("script: " + relative));
        return "script: " + relative + "\n" + yaml;
    }

    private static List<String> extras(Path dir, Layout layout, Map<String, String> files)
            throws IOException {
        List<String> out = new ArrayList<>();
        String ignore = TemplateWriter.read("empty", "dot-gitignore");
        String env = TemplateWriter.read("empty", "dot-env.example");
        if (ignore != null && !Files.exists(dir.resolve(".gitignore"))) {
            Files.writeString(dir.resolve(".gitignore"), ignore, StandardCharsets.UTF_8);
            out.add(".gitignore");
        }
        if (env != null && !Files.exists(dir.resolve(".env.example"))) {
            Files.writeString(dir.resolve(".env.example"), env, StandardCharsets.UTF_8);
            out.add(".env.example");
        }
        return out;
    }

    static String slug(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-");
    }

    /** The file with a header that says what made it and from which plan. */
    static String stamped(String path, String content, Stamp stamp, String planHash) {
        content = withoutHeader(content);
        String line =
                "Generated by "
                        + stamp.generator()
                        + " on "
                        + stamp.date()
                        + " from plan "
                        + planHash
                        + ".";
        if (path.endsWith(".loom") || path.endsWith(".loot")) return "// " + line + "\n" + content;
        if (path.endsWith(".yaml") || path.endsWith(".properties"))
            return "# " + line + "\n" + content;
        if (path.endsWith(".md")) return "<!-- " + line + " -->\n" + content;
        return content;
    }

    private static final Pattern HEADER =
            Pattern.compile("\\A(?://|#|<!--) Generated by [^\\n]*\\R");

    /** The text without a header an earlier generation put on it, so a file is stamped once. */
    static String withoutHeader(String content) {
        return HEADER.matcher(content).replaceFirst("");
    }

    /**
     * A trial copy of a project with some files changed, for the gates to run on: every file as it
     * is, no header added, the script where the project keeps it. The project on disk is not
     * touched.
     */
    public static Workspace trial(Project project, Map<String, String> files, Path tmpRoot)
            throws IOException {
        Path dir = Files.createTempDirectory(tmpRoot, "weave-edit-");
        List<String> written = new ArrayList<>();
        try {
            for (var e : files.entrySet()) {
                String rel = project.relOf(e.getKey());
                Path p = dir.resolve(rel);
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
                written.add(rel);
            }
            for (var e : project.readOnly().entrySet()) {
                Path p = dir.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            new Workspace(dir, written).close();
            throw e;
        }
        return Workspace.owned(dir, written, dir.resolve(project.relOf("main.loom")));
    }

    /**
     * The golden dataset stub the generator writes itself (not the model): the quality dimensions
     * and one scenario for the whole workflow, to be filled in with real cases and run with {@code
     * weave eval}.
     */
    public static Map<String, String> datasetStub(Plan plan) {
        Map<String, String> out = new TreeMap<>();
        out.put(
                "eval/golden/dataset.yaml",
                "dimensions:\n  behaviour: Does what the plan says\n  safety: Nothing is sent or changed without the approval the plan names\n");
        out.put(
                "eval/golden/workflow.yaml",
                "- id: workflow-001\n  name: A typical run\n  input: \"a typical input\"\n  dimensions: [behaviour, safety]\n  rubric:\n    - \""
                        + plan.summary().replace("\"", "'").replace("\n", " ")
                        + "\"\n");
        return out;
    }

    /** The names of files a workspace must hold for the gates to mean anything. */
    public static Set<String> required() {
        return Set.of("main.loom");
    }
}
