package io.github.llm4j.model;

import java.util.Map;
import java.util.Objects;

/**
 * A tool the model asked for.
 *
 * @param id the provider's id for the call (null when the provider sends none, as Gemini does)
 * @param name the tool's name
 * @param arguments the arguments as the model's JSON object
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        Objects.requireNonNull(name, "name cannot be null");
        arguments = arguments == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(arguments));
    }

    /** The same call with an id (the agent gives ids to calls that arrive without one). */
    public ToolCall withId(String newId) {
        return new ToolCall(newId, name, arguments);
    }
}
