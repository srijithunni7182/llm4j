package io.github.llm4j.model;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A tool as it is offered to a model that supports native tool calling.
 *
 * @param name the function name: letters, digits, {@code _} or {@code -}, at most 64 characters (the common ground of Gemini and Claude)
 * @param description what the tool does and when to use it
 * @param parameters a JSON Schema object describing the arguments
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {

    private static final Pattern LEGAL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public ToolSpec {
        Objects.requireNonNull(name, "name cannot be null");
        if (!isLegalName(name)) {
            throw new IllegalArgumentException("a tool name must be 1-64 letters, digits, _ or -: " + name);
        }
        description = description == null ? "" : description;
        parameters = parameters == null ? ToolSchema.permissive() : Map.copyOf(parameters);
    }

    /** Whether {@code name} can be sent to a model as a function name. */
    public static boolean isLegalName(String name) {
        return name != null && LEGAL_NAME.matcher(name).matches();
    }
}
