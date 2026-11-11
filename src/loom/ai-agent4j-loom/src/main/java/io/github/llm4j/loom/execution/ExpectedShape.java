package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.SchemaDef;
import java.util.List;
import java.util.Map;

/**
 * Checks a value that came from outside (a reply to an {@code ask}, the result of a remote task)
 * against the {@code expecting} shape the script declared, so the workflow never sees a value that is
 * not what it asked for. Fields the shape does not name are ignored: a newer remote agent's extra
 * fields must not break a caller.
 */
public final class ExpectedShape {

    private ExpectedShape() {}

    /**
     * What is wrong with the value, or null if it fits.
     *
     * @param shape the declared shape
     * @param value the parsed value (a map, list, string, number or boolean), or null
     */
    public static String problem(SchemaDef shape, Object value) {
        return problem(shape, value, "the answer");
    }

    private static String problem(SchemaDef shape, Object value, String where) {
        if (value == null) return where + " is missing";
        switch (shape.getType()) {
            case OBJECT -> {
                if (!(value instanceof Map<?, ?> map)) return where + " should be an object";
                if (shape.getFields() != null) {
                    for (Map.Entry<String, SchemaDef> field : shape.getFields().entrySet()) {
                        if (!map.containsKey(field.getKey()))
                            return where + " has no field " + field.getKey();
                        String inner = problem(field.getValue(), map.get(field.getKey()), field.getKey());
                        if (inner != null) return inner;
                    }
                }
                return null;
            }
            case LIST -> {
                if (!(value instanceof List<?> list)) return where + " should be a list";
                if (shape.getElementType() != null) {
                    for (int i = 0; i < list.size(); i++) {
                        String inner = problem(shape.getElementType(), list.get(i), where + "[" + i + "]");
                        if (inner != null) return inner;
                    }
                }
                return null;
            }
            case ENUM -> {
                List<String> allowed = shape.getEnumValues() == null ? List.of() : shape.getEnumValues();
                return allowed.contains(String.valueOf(value))
                        ? null
                        : where + " is " + value + ", which is not one of " + String.join(", ", allowed);
            }
            case NUMBER -> {
                return value instanceof Number ? null : where + " should be a number";
            }
            case BOOLEAN -> {
                return value instanceof Boolean ? null : where + " should be true or false";
            }
            default -> {
                return value instanceof String ? null : where + " should be text";
            }
        }
    }
}
