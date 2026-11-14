package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.runtime.VariableContext;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Turns the text a script wrote into the text an agent is sent: {@code {name}} and {@code
 * {var.field}} placeholders filled from the run's variables (nothing else is replaced), a schema
 * written as the words a model is told, and a model's JSON answer read back into a value.
 */
final class PayloadText {
    private static final Logger log = Logger.getLogger(HarnessExecutor.class.getName());

    /** What placeholder filling needs from the run. */
    interface Host {
        /** Variables as the current block sees them: workflow variables plus block-local names. */
        VariableContext view();

        /** The workflow's own variables. */
        VariableContext context();

        /**
         * A variable or {@code var.field} path whose value was put into the text. The runtime
         * uses it to work out where the finished text came from (docs/specs/26, R7).
         */
        default void touched(String path) {}
    }

    private static final java.util.regex.Pattern PAYLOAD_PATH =
            java.util.regex.Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)}");
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{([^{}\\s]+)}");

    private final Host host;

    PayloadText(Host host) {
        this.host = host;
    }

    String resolve(String rawPayload) {
        if (rawPayload == null) return null;
        // Only {name} and {var.field.sub} are replaced, in one pass over the text the script wrote,
        // so a value just inserted is never read again and one variable's text can't rewrite another's.
        VariableContext scope = host.view();
        Map<String, Object> vars = scope.getAll();
        java.util.regex.Matcher m = PLACEHOLDER.matcher(rawPayload);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(rawPayload, last, m.start());
            String name = m.group(1);
            if (PAYLOAD_PATH.matcher(m.group()).matches()) {
                // Like conditions, a missing field reads as empty (e.g. a step that failed and set
                // nothing).
                Object value =
                        io.github.llm4j.loom.runtime.ConditionEvaluator.resolvePath(name, scope);
                if (value != null) host.touched(name);
                out.append(value != null ? text(name, value) : "");
            } else if (vars.containsKey(name)) {
                host.touched(name);
                out.append(text(name, vars.get(name)));
            } else {
                out.append(m.group()); // not a variable: left as written
            }
            last = m.end();
        }
        out.append(rawPayload.substring(last));
        return out.toString();
    }

    /**
     * A value as the text it becomes. A handle is held by the script and never written into text a
     * model could read, and the absent result of a child that did not complete raises the status
     * error: both fail closed here, at the one place a placeholder is filled (phase 34, F2 and F7),
     * so the static check is not the only line.
     */
    private static String text(String name, Object value) {
        if (value instanceof io.github.llm4j.loom.runtime.ChildHandle)
            throw new IllegalStateException(
                    "{" + name + "} is a handle, and a handle is never written into text a model could read."
                            + " Read a fact instead, such as {" + name + ".status}.");
        if (value instanceof io.github.llm4j.loom.runtime.Unavailable gone)
            throw new IllegalStateException(gone.message());
        return String.valueOf(value);
    }

    static String stringify(SchemaDef schema) {
        if (schema == null) return "any";
        return switch (schema.getType()) {
            case OBJECT -> "{"
                    + schema.getFields().entrySet().stream()
                            .map(e -> e.getKey() + ": " + stringify(e.getValue()))
                            .collect(java.util.stream.Collectors.joining(", "))
                    + "}";
            case LIST -> "list<" + stringify(schema.getElementType()) + ">";
            case ENUM -> "enum" + schema.getEnumValues().toString();
            default -> schema.getType().name().toLowerCase();
        };
    }

    static Object parseJson(String raw) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String jsonPart = raw;
            if (raw.contains("```json")) {
                jsonPart = raw.substring(raw.indexOf("```json") + 7, raw.lastIndexOf("```"));
            } else if (raw.contains("```")) {
                jsonPart = raw.substring(raw.indexOf("```") + 3, raw.lastIndexOf("```"));
            }
            return mapper.readValue(jsonPart.trim(), Object.class);
        } catch (Exception e) {
            log.warning(
                    "Failed to parse JSON result: " + e.getMessage() + ". Returning raw string.");
            return raw;
        }
    }
}
