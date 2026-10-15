package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.runtime.VariableContext;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Turns the text a script wrote into the text an agent is sent: {@code {name}} and {@code {var.field}} placeholders filled from the run's variables (and the
 * legacy bare names), a schema written as the words a model is told, and a model's JSON answer read back into a value.
 */
final class PayloadText {
    private static final Logger log = Logger.getLogger(HarnessExecutor.class.getName());

    /** What placeholder filling needs from the run. */
    interface Host {
        /** Variables as the current block sees them: workflow variables plus block-local names. */
        VariableContext view();

        /** The workflow's own variables. */
        VariableContext context();
    }

    private static final java.util.regex.Pattern PAYLOAD_PATH =
            java.util.regex.Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)}");
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{([^{}\\s]+)}");

    private final Host host;

    PayloadText(Host host) {
        this.host = host;
    }

    String resolve(String rawPayload) {
        if (rawPayload == null) return null;
        // {name} and {var.field.sub} are replaced by values; bare names (the legacy form) only in the text the
        // script wrote — never inside a value just inserted, so one variable's text can't rewrite another's.
        VariableContext scope = host.view();
        Map<String, Object> vars = scope.getAll();
        java.util.regex.Matcher m = PLACEHOLDER.matcher(rawPayload);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(bareNames(rawPayload.substring(last, m.start())));
            String name = m.group(1);
            if (PAYLOAD_PATH.matcher(m.group()).matches()) {
                // Like conditions, a missing field reads as empty (e.g. a step that failed and set nothing).
                Object value = io.github.llm4j.loom.runtime.ConditionEvaluator.resolvePath(name, scope);
                out.append(value != null ? String.valueOf(value) : "");
            } else if (vars.containsKey(name)) {
                out.append(String.valueOf(vars.get(name)));
            } else {
                out.append(m.group()); // not a variable: left as written
            }
            last = m.end();
        }
        out.append(bareNames(rawPayload.substring(last)));
        return out.toString();
    }

    /**
     * Bare-name substitution, for backward compatibility with older scripts. Workflow variables only:
     * block-local names (for each items, _error) are common words, so they must be written {like.this}.
     */
    private String bareNames(String text) {
        if (text.isEmpty()) return text;
        Map<String, Object> vars = host.context().getAll();
        if (vars.isEmpty()) return text;
        // One pass, longest names first: a value put in is never scanned again.
        String alternatives = vars.keySet().stream().filter(k -> !k.isEmpty())
                .sorted((x, y) -> Integer.compare(y.length(), x.length()))
                .map(java.util.regex.Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"));
        if (alternatives.isEmpty()) return text;
        return java.util.regex.Pattern.compile(alternatives).matcher(text)
                .replaceAll(r -> java.util.regex.Matcher.quoteReplacement(String.valueOf(vars.get(r.group()))));
    }

    static String stringify(SchemaDef schema) {
        if (schema == null) return "any";
        return switch (schema.getType()) {
            case OBJECT -> "{" + schema.getFields().entrySet().stream()
                    .map(e -> e.getKey() + ": " + stringify(e.getValue()))
                    .collect(java.util.stream.Collectors.joining(", ")) + "}";
            case LIST -> "list<" + stringify(schema.getElementType()) + ">";
            case ENUM -> "enum" + schema.getEnumValues().toString();
            default -> schema.getType().name().toLowerCase();
        };
    }

    static Object parseJson(String raw) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String jsonPart = raw;
            if (raw.contains("```json")) {
                jsonPart = raw.substring(raw.indexOf("```json") + 7, raw.lastIndexOf("```"));
            } else if (raw.contains("```")) {
                jsonPart = raw.substring(raw.indexOf("```") + 3, raw.lastIndexOf("```"));
            }
            return mapper.readValue(jsonPart.trim(), Object.class);
        } catch (Exception e) {
            log.warning("Failed to parse JSON result: " + e.getMessage() + ". Returning raw string.");
            return raw;
        }
    }
}
