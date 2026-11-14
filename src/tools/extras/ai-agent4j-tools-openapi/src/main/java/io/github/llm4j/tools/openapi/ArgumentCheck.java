package io.github.llm4j.tools.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.tools.ToolRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks the arguments a model gave a tool against the operation's own parameters (phase 36a, D3):
 * an argument the operation does not have is refused, a required one must be there, and each must
 * be of the type the description says. The server still has the last word on anything finer.
 */
final class ArgumentCheck {

    private ArgumentCheck() {}

    /**
     * @throws ToolRefusal naming every problem, so the model can write the call again
     */
    static void check(ApiOperation op, Map<String, Object> args) {
        List<String> problems = new ArrayList<>();
        Set<String> allowed = new TreeSet<>();
        for (ApiOperation.Parameter p : op.parameters()) allowed.add(p.argument());
        if (op.body() != null) allowed.add("body");
        for (String given : args.keySet())
            if (!allowed.contains(given))
                problems.add(
                        "unknown argument "
                                + given
                                + (allowed.isEmpty() ? " (this tool takes none)" : " (it takes "
                                        + String.join(", ", allowed)
                                        + ")"));
        for (ApiOperation.Parameter p : op.parameters()) {
            Object value = args.get(p.argument());
            if (value == null) {
                if (p.required()) problems.add(p.argument() + " is required");
                continue;
            }
            String why = mismatch(p.schema(), value);
            if (why != null) problems.add(p.argument() + " " + why);
        }
        if (op.body() != null) {
            Object body = args.get("body");
            if (body == null && op.body().required()) problems.add("body is required");
        }
        if (!problems.isEmpty()) throw new ToolRefusal(String.join("; ", problems));
    }

    /** What is wrong with {@code value} for a schema of this type, or null. */
    private static String mismatch(JsonNode schema, Object value) {
        String type = schema == null ? "" : schema.path("type").asText("");
        switch (type) {
            case "integer":
                if (!(value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()))
                        && !(value instanceof CharSequence s && s.toString().matches("-?\\d+")))
                    return "must be a whole number";
                break;
            case "number":
                if (!(value instanceof Number)
                        && !(value instanceof CharSequence s
                                && s.toString().matches("-?\\d+(\\.\\d+)?")))
                    return "must be a number";
                break;
            case "boolean":
                if (!(value instanceof Boolean)
                        && !(value instanceof CharSequence s
                                && s.toString().matches("true|false")))
                    return "must be true or false";
                break;
            case "array":
                if (!(value instanceof List<?>)) return "must be a list";
                break;
            case "object":
                if (!(value instanceof Map<?, ?>)) return "must be an object";
                break;
            default:
                if (value instanceof Map<?, ?> || value instanceof List<?>)
                    return "must be text, not a list or object";
        }
        JsonNode allowed = schema == null ? null : schema.get("enum");
        if (allowed != null && allowed.isArray() && !(value instanceof List<?>)) {
            String text = String.valueOf(value);
            boolean found = false;
            for (JsonNode option : allowed) found |= option.asText().equals(text);
            if (!found) {
                List<String> options = new ArrayList<>();
                allowed.forEach(o -> options.add(o.asText()));
                return "must be one of " + String.join(", ", options);
            }
        }
        return null;
    }
}
