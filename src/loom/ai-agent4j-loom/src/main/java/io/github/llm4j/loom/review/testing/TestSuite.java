package io.github.llm4j.loom.review.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The test files that belong to a script, read for what review needs to know about them: which
 * agents and classifiers they script, whether they inject failures or supply answers for people,
 * and which names they use. Review only reads them; it never runs a test (R34).
 *
 * <p>A test file is {@code tests/*.test.yaml} beside the script. It belongs to the script when its
 * {@code script:} points at it, or when it says nothing about which script it is for.
 */
public final class TestSuite {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** One test file. */
    public record TestFile(
            String path,
            Set<String> scripted,
            boolean injectsFailures,
            boolean suppliesHumans,
            Set<String> agentNames,
            Set<String> toolNames,
            Set<String> taskNames) {}

    private final List<TestFile> files;
    private final List<String> unreadable;

    private TestSuite(List<TestFile> files, List<String> unreadable) {
        this.files = List.copyOf(files);
        this.unreadable = List.copyOf(unreadable);
    }

    /** No tests. */
    public static TestSuite none() {
        return new TestSuite(List.of(), List.of());
    }

    /** The test files beside {@code script} (in {@code tests/}) that belong to it. */
    public static TestSuite beside(Path script) {
        Path abs = script.toAbsolutePath().normalize();
        Path dir = abs.getParent() == null ? Path.of("tests") : abs.getParent().resolve("tests");
        if (!Files.isDirectory(dir)) return none();
        List<TestFile> files = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        try (Stream<Path> list = Files.list(dir)) {
            for (Path f :
                    list.filter(p -> p.getFileName().toString().endsWith(".test.yaml"))
                            .sorted()
                            .toList()) {
                try {
                    JsonNode root = YAML.readTree(Files.readString(f));
                    if (root == null || !root.isObject()) {
                        bad.add(f.toString());
                        continue;
                    }
                    if (root.hasNonNull("script")) {
                        Path target =
                                f.getParent().resolve(root.get("script").asText()).normalize();
                        if (!target.equals(abs)) continue;
                    }
                    files.add(read(f.toString(), root));
                } catch (IOException | RuntimeException e) {
                    bad.add(f.toString());
                }
            }
        } catch (IOException e) {
            return none();
        }
        return new TestSuite(files, bad);
    }

    private static TestFile read(String path, JsonNode root) {
        Set<String> scripted = new LinkedHashSet<>();
        Set<String> agents = new LinkedHashSet<>();
        Set<String> tools = new LinkedHashSet<>();
        Set<String> tasks = new LinkedHashSet<>();
        boolean fail = false;
        boolean humans = false;
        List<JsonNode> scopes = new ArrayList<>();
        if (root.has("defaults")) scopes.add(root.get("defaults"));
        if (root.has("cases") && root.get("cases").isArray())
            root.get("cases").forEach(scopes::add);
        for (JsonNode c : scopes) {
            keys(c.get("models"), scripted);
            keys(c.get("models"), agents);
            keys(c.get("fail"), agents);
            keys(c.get("tools"), tools);
            keys(c.get("tasks"), tasks);
            if (c.has("expect")) {
                keys(c.get("expect").get("calls"), agents);
                keys(c.get("expect").get("attempts"), agents);
            }
            fail |= c.hasNonNull("fail") && c.get("fail").size() > 0;
            humans |= c.hasNonNull("humans") && c.get("humans").size() > 0;
        }
        return new TestFile(path, scripted, fail, humans, agents, tools, tasks);
    }

    private static void keys(JsonNode map, Set<String> into) {
        if (map != null && map.isObject()) map.fieldNames().forEachRemaining(into::add);
    }

    public List<TestFile> files() {
        return files;
    }

    /** Test files that could not be read, for a note. */
    public List<String> unreadable() {
        return unreadable;
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    /** True when some test scripts the agent or classifier. */
    public boolean scripts(String name) {
        return files.stream().anyMatch(f -> f.scripted().contains(name));
    }

    public boolean injectsFailures() {
        return files.stream().anyMatch(TestFile::injectsFailures);
    }

    public boolean suppliesHumans() {
        return files.stream().anyMatch(TestFile::suppliesHumans);
    }
}
