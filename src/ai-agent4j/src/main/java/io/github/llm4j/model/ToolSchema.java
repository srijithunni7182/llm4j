package io.github.llm4j.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON Schema for a tool's arguments:
 *
 * <pre>{@code
 * ToolSchema.object().string("query", "what to search for", true).integer("limit", "how many results", false).build()
 * }</pre>
 */
public final class ToolSchema {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private ToolSchema() {}

    public static ToolSchema object() {
        return new ToolSchema();
    }

    /** What a tool that declares nothing gets: any object. The model has to guess argument names from the description. */
    public static Map<String, Object> permissive() {
        return Map.of("type", "object", "properties", Map.of(), "additionalProperties", true);
    }

    public ToolSchema string(String name, String description, boolean isRequired) {
        return property(name, "string", description, isRequired);
    }

    public ToolSchema number(String name, String description, boolean isRequired) {
        return property(name, "number", description, isRequired);
    }

    public ToolSchema integer(String name, String description, boolean isRequired) {
        return property(name, "integer", description, isRequired);
    }

    public ToolSchema bool(String name, String description, boolean isRequired) {
        return property(name, "boolean", description, isRequired);
    }

    /** A list of strings. */
    public ToolSchema stringArray(String name, String description, boolean isRequired) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        if (description != null && !description.isBlank()) p.put("description", description);
        p.put("items", Map.of("type", "string"));
        properties.put(name, p);
        if (isRequired) required.add(name);
        return this;
    }

    /** A string argument restricted to the given values. */
    public ToolSchema enumeration(String name, String description, boolean isRequired, List<String> values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        if (description != null && !description.isBlank()) p.put("description", description);
        p.put("enum", List.copyOf(values));
        properties.put(name, p);
        if (isRequired) required.add(name);
        return this;
    }

    private ToolSchema property(String name, String type, String description, boolean isRequired) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        if (description != null && !description.isBlank()) p.put("description", description);
        properties.put(name, p);
        if (isRequired) required.add(name);
        return this;
    }

    public Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) schema.put("required", List.copyOf(required));
        return schema;
    }
}
