package io.github.llm4j.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks a tool call's arguments against the tool's own parameter schema: the required ones are
 * there, and each given one has the declared type and, where the schema lists values, one of them.
 * It reads only the parts of JSON Schema that {@link io.github.llm4j.model.ToolSchema} writes
 * (object properties, {@code required}, {@code type}, {@code enum}, array {@code items}); anything
 * else in a schema is not checked. A schema that declares no properties (the permissive default)
 * accepts anything.
 */
final class ArgumentCheck {

    private ArgumentCheck() {}

    /**
     * What is wrong with the arguments, as a sentence the model can act on; null when nothing is.
     */
    @SuppressWarnings("unchecked")
    static String problems(Map<String, Object> schema, Map<String, Object> args) {
        if (schema == null) return null;
        List<String> found = new ArrayList<>();
        Object required = schema.get("required");
        if (required instanceof List<?> names) {
            for (Object name : names) {
                if (!args.containsKey(String.valueOf(name))
                        || args.get(String.valueOf(name)) == null)
                    found.add("'" + name + "' is required but missing");
            }
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<String, Object> given : args.entrySet()) {
                Object declared = properties.get(given.getKey());
                if (declared instanceof Map<?, ?> property && given.getValue() != null) {
                    String problem =
                            check(given.getKey(), (Map<String, Object>) property, given.getValue());
                    if (problem != null) found.add(problem);
                }
            }
        }
        return found.isEmpty() ? null : String.join("; ", found);
    }

    @SuppressWarnings("unchecked")
    private static String check(String name, Map<String, Object> property, Object value) {
        Object type = property.get("type");
        if (type instanceof String t && !hasType(t, value)) {
            return "'" + name + "' must be " + article(t) + " " + t + " but was " + describe(value);
        }
        if (property.get("enum") instanceof List<?> allowed && !allowed.contains(value)) {
            return "'" + name + "' must be one of " + allowed + " but was " + describe(value);
        }
        if ("array".equals(type)
                && value instanceof List<?> items
                && property.get("items") instanceof Map<?, ?> itemSchema) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) == null) continue;
                String problem =
                        check(name + "[" + i + "]", (Map<String, Object>) itemSchema, items.get(i));
                if (problem != null) return problem;
            }
        }
        return null;
    }

    private static boolean hasType(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer
                    || value instanceof Long
                    || value instanceof Short
                    || value instanceof Byte
                    || (value instanceof Number n
                            && n.doubleValue() == Math.rint(n.doubleValue())
                            && !(value instanceof Float)
                            && !(value instanceof Double));
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> true; // a type this check does not know is not a reason to refuse
        };
    }

    private static String article(String type) {
        return switch (type) {
            case "integer", "array", "object" -> "an";
            default -> "a";
        };
    }

    private static String describe(Object value) {
        String kind =
                value instanceof String
                        ? "a string"
                        : value instanceof Boolean
                                ? "a boolean"
                                : value instanceof Number
                                        ? "a number"
                                        : value instanceof List
                                                ? "an array"
                                                : value instanceof Map
                                                        ? "an object"
                                                        : "something else";
        String shown = String.valueOf(value);
        return kind + " (" + (shown.length() > 60 ? shown.substring(0, 57) + "..." : shown) + ")";
    }
}
