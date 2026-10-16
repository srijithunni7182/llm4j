package io.github.llm4j.nirmaan.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The technical spec, kept as data. Rishi answers with fields (a stack, three commands, a plan); the factory stores them as
 * {@code meta/spec.json} and renders {@code SPEC.md} from them, so no later step ever has to dig a command out of prose.
 */
final class SpecStore {

    static final String FILE = "spec.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The commands a spec must give, and whether "None" is acceptable for it. */
    static final Map<String, Boolean> COMMANDS = Map.of("build_command", true, "test_command", false, "run_command", true, "e2e_command", true);

    private SpecStore() {}

    static void save(Path meta, Map<String, Object> spec) throws IOException {
        Files.writeString(meta.resolve(FILE), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(spec));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> load(Path meta) throws IOException {
        Path file = meta.resolve(FILE);
        return Files.isRegularFile(file) ? JSON.readValue(file.toFile(), Map.class) : null;
    }

    /** What is wrong with a spec, or null when it is usable. */
    static String problem(Map<String, Object> spec) {
        for (Map.Entry<String, Boolean> command : COMMANDS.entrySet()) {
            String value = text(spec, command.getKey());
            boolean none = value.isBlank() || value.equalsIgnoreCase("none");
            if (none && !command.getValue()) return command.getKey() + " is missing: without it nothing can be tested";
        }
        if (text(spec, "stack").isBlank()) return "stack is missing";
        return null;
    }

    static String text(Map<String, ?> spec, String key) {
        Object value = spec.get(key);
        return value == null ? "" : String.valueOf(value).strip();
    }

    static String render(Map<String, ?> spec) {
        StringBuilder md = new StringBuilder("# Technical Specification\n\n");
        md.append("## Tech Stack\n").append(text(spec, "stack")).append("\n\n");
        md.append("## Rationale\n").append(text(spec, "rationale")).append("\n\n");
        section(md, "Requirements", spec.get("requirements"), "- ");
        md.append("## Test Strategy\n- Unit tests: ").append(text(spec, "unit_test_strategy")).append("\n- End to end: ").append(text(spec, "e2e_strategy")).append("\n\n");
        md.append("## Commands\nBuild Command: ").append(text(spec, "build_command"))
                .append("\nTest Command: ").append(text(spec, "test_command"))
                .append("\nRun Command: ").append(text(spec, "run_command"))
                .append("\nE2E Command: ").append(text(spec, "e2e_command")).append("\n\n");
        section(md, "Project Structure", spec.get("structure"), "- ");
        section(md, "Implementation Plan", spec.get("plan"), "1. ");
        if (!text(spec, "change_summary").isBlank()) md.append("## Last Change\n").append(text(spec, "change_summary")).append("\n");
        return md.toString();
    }

    private static void section(StringBuilder md, String title, Object items, String bullet) {
        md.append("## ").append(title).append('\n');
        if (items instanceof List<?> list) list.forEach(item -> md.append(bullet).append(item).append('\n'));
        md.append('\n');
    }

    /** The fields {@code LoadSpec} hands back to the workflow. */
    static Map<String, Object> view(Map<String, Object> spec) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("markdown", render(spec));
        for (String key : List.of("stack", "build_command", "test_command", "run_command", "e2e_command")) view.put(key, text(spec, key));
        return view;
    }
}
