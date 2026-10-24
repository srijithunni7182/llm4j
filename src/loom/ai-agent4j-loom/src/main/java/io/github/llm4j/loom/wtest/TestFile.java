package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A test file ({@code *.test.yaml}): the script it is for, the workflow, and its cases with the
 * file's {@code defaults} already merged into each. Reading only parses; {@link TestChecker} says
 * whether the cases make sense for the script.
 */
public final class TestFile {

    private static final YAMLMapper YAML = new YAMLMapper();

    /** One case. {@code body} is the merged map (defaults, then the case itself). */
    public record Case(String file, String name, ObjectNode body) {

        public JsonNode get(String key) {
            return body.get(key);
        }

        public boolean has(String key) {
            return body.has(key) && !body.get(key).isNull();
        }

        public List<String> tags() {
            List<String> tags = new ArrayList<>();
            if (body.has("tags") && body.get("tags").isArray())
                body.get("tags").forEach(t -> tags.add(t.asText()));
            return tags;
        }

        /** The case as YAML, merged, for {@code --show-case}. */
        public String yaml() {
            try {
                return YAML.writeValueAsString(body);
            } catch (IOException e) {
                return body.toString();
            }
        }
    }

    private final Path path;
    private final String script;
    private final String workflow;
    private final List<Case> cases;
    private final List<String> problems;
    private final JsonNode captured;

    private TestFile(
            Path path,
            String script,
            String workflow,
            List<Case> cases,
            List<String> problems,
            JsonNode captured) {
        this.captured = captured;
        this.path = path;
        this.script = script;
        this.workflow = workflow;
        this.cases = List.copyOf(cases);
        this.problems = List.copyOf(problems);
    }

    public Path path() {
        return path;
    }

    /**
     * The {@code captured:} block of a case taken from a run (where it came from, whether a person
     * has approved it), or null for a hand-written file.
     */
    public JsonNode captured() {
        return captured;
    }

    /** The state of a captured file: observed, approved or retired; null when not captured. */
    public String capturedStatus() {
        return captured != null && captured.hasNonNull("status")
                ? captured.get("status").asText()
                : null;
    }

    /** The {@code script:} of the file, relative to it, or null. */
    public String script() {
        return script;
    }

    /** The {@code workflow:} of the file, or null. */
    public String workflow() {
        return workflow;
    }

    public List<Case> cases() {
        return cases;
    }

    /** What could not be read: a syntax error, a case without a name. */
    public List<String> problems() {
        return problems;
    }

    /** Reads a test file; a file that cannot be read has no cases and says why. */
    public static TestFile read(Path path) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            return new TestFile(
                    path, null, null, List.of(), List.of(path + ": " + firstLine(e)), null);
        }
        return parse(path, text);
    }

    /** As {@link #read(Path)} for text that is not (or not yet) a file; {@code path} names it. */
    public static TestFile parse(Path path, String text) {
        List<String> problems = new ArrayList<>();
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (IOException | RuntimeException e) {
            String where =
                    e instanceof com.fasterxml.jackson.core.JsonProcessingException j
                                    && j.getLocation() != null
                                    && j.getLocation().getLineNr() > 0
                            ? " (line " + j.getLocation().getLineNr() + ")"
                            : "";
            return new TestFile(
                    path, null, null, List.of(), List.of(path + ": " + firstLine(e) + where), null);
        }
        if (root == null || !root.isObject())
            return new TestFile(
                    path,
                    null,
                    null,
                    List.of(),
                    List.of(path + ": a test file is a map with cases"),
                    null);
        String file = path.toString();
        ObjectNode defaults =
                root.has("defaults") && root.get("defaults").isObject()
                        ? (ObjectNode) root.get("defaults")
                        : YAML.createObjectNode();
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!List.of("script", "workflow", "defaults", "cases", "captured").contains(key))
                problems.add(
                        file
                                + ": unknown key '"
                                + key
                                + "' (a test file has script, workflow, defaults, cases and, when taken from a run, captured)");
        }
        List<Case> cases = new ArrayList<>();
        JsonNode list = root.get("cases");
        if (list == null || !list.isArray() || list.isEmpty())
            problems.add(file + ": cases: a list of at least one case");
        else
            for (int i = 0; i < list.size(); i++) {
                JsonNode c = list.get(i);
                if (!c.isObject()) {
                    problems.add(file + ": case " + (i + 1) + " is not a map");
                    continue;
                }
                String name = c.hasNonNull("name") ? c.get("name").asText() : null;
                if (name == null || name.isBlank()) {
                    problems.add(file + ": case " + (i + 1) + " has no name");
                    continue;
                }
                cases.add(new Case(file, name, merge(defaults, (ObjectNode) c)));
            }
        return new TestFile(
                path,
                root.hasNonNull("script") ? root.get("script").asText() : null,
                root.hasNonNull("workflow") ? root.get("workflow").asText() : null,
                cases,
                problems,
                root.has("captured") && root.get("captured").isObject()
                        ? root.get("captured")
                        : null);
    }

    /** Maps merge by key, the case winning; anything else (a list, a value) is replaced. */
    static ObjectNode merge(ObjectNode base, ObjectNode over) {
        ObjectNode out = base.deepCopy();
        for (Iterator<Map.Entry<String, JsonNode>> it = over.fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode existing = out.get(e.getKey());
            if (existing != null && existing.isObject() && e.getValue().isObject())
                out.set(e.getKey(), merge((ObjectNode) existing, (ObjectNode) e.getValue()));
            else out.set(e.getKey(), e.getValue().deepCopy());
        }
        return out;
    }

    private static String firstLine(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.lines().findFirst().orElse("");
    }
}
