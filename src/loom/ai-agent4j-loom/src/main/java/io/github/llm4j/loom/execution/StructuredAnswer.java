package io.github.llm4j.loom.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.ast.SchemaDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads an agent's answer as the structured value its {@code output_schema} (or a step's {@code expecting}) promised, and says exactly
 * what is wrong when it is not that.
 *
 * <p>Until this existed an answer that was not JSON, or did not have the promised fields, was passed on as raw text with a log line, so
 * a later step failed far from the cause (or worse, carried on with nothing). Now the answer is checked where it arrives:
 * <ul>
 *   <li>the JSON may be wrapped in a code fence or in a sentence of chatter, and is still found (the first complete object or list);</li>
 *   <li>every field the schema names must be present and of its type; enum values must be exactly one of the listed words;
 *       fields the schema does not name are ignored;</li>
 *   <li>a violation throws {@link SchemaViolation}, which the delegate loop treats like any failed attempt: it is retried (with a
 *       correction), and when the retries run out the step fails and its {@code on_failure} runs.</li>
 * </ul>
 */
final class StructuredAnswer {

    /** An answer that does not match its schema. The message names every problem (at most five), by path. */
    static final class SchemaViolation extends RuntimeException {
        private final List<String> problems;

        SchemaViolation(List<String> problems) {
            super("the answer did not match the schema: " + String.join("; ", problems.subList(0, Math.min(5, problems.size())))
                    + (problems.size() > 5 ? "; and " + (problems.size() - 5) + " more" : ""));
            this.problems = List.copyOf(problems);
        }

        List<String> problems() {
            return problems;
        }

        /** What to tell the model on the next attempt. */
        String correction() {
            return "\n\nYour previous reply was rejected: " + String.join("; ", problems.subList(0, Math.min(5, problems.size())))
                    + ". Reply again with ONE JSON object only that follows the schema: no YAML, no markdown, no text before or after it, no code fences.";
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private StructuredAnswer() {}

    /** The answer as a map, list or scalar; throws {@link SchemaViolation} when it is not valid JSON or does not match {@code schema}. */
    static Object parse(String raw, SchemaDef schema) {
        String json = extract(raw, schema);
        Object value;
        try {
            value = JSON.readValue(json, Object.class);
        } catch (Exception e) {
            throw new SchemaViolation(List.of("the answer is not valid JSON (" + firstLine(e.getMessage()) + ")"));
        }
        List<String> problems = problems(value, schema);
        if (!problems.isEmpty()) throw new SchemaViolation(problems);
        return value;
    }

    /** The answer as JSON when it is JSON (found the same tolerant way), and the raw text when it is not. Nothing is checked against the schema. */
    static Object parseOrRaw(String raw, SchemaDef schema) {
        try {
            return JSON.readValue(extract(raw, schema), Object.class);
        } catch (Exception notJson) {
            return raw;
        }
    }

    /** Everything wrong with {@code value} against {@code schema}, as {@code path: problem}; empty when it matches. */
    static List<String> problems(Object value, SchemaDef schema) {
        List<String> out = new ArrayList<>();
        check(value, schema, "answer", out);
        return out;
    }

    private static void check(Object value, SchemaDef schema, String path, List<String> out) {
        if (schema == null) return;
        if (value == null) {
            out.add(path + ": missing");
            return;
        }
        switch (schema.getType()) {
            case OBJECT -> {
                if (!(value instanceof Map<?, ?> map)) {
                    out.add(path + ": expected an object, got " + describe(value));
                    return;
                }
                for (Map.Entry<String, SchemaDef> field : schema.getFields().entrySet()) {
                    String at = path.equals("answer") ? field.getKey() : path + "." + field.getKey();
                    if (!map.containsKey(field.getKey()) || map.get(field.getKey()) == null) out.add(at + ": missing");
                    else check(map.get(field.getKey()), field.getValue(), at, out);
                }
            }
            case LIST -> {
                if (!(value instanceof List<?> list)) {
                    out.add(path + ": expected a list, got " + describe(value));
                    return;
                }
                if (schema.getElementType() == null) return;
                for (int i = 0; i < list.size(); i++) check(list.get(i), schema.getElementType(), path + "." + i, out);
            }
            case ENUM -> {
                if (!(value instanceof CharSequence) || !schema.getEnumValues().contains(value.toString())) {
                    out.add(path + ": " + describe(value) + " is not one of " + schema.getEnumValues());
                }
            }
            case STRING -> {
                // a number or true/false where text was asked for is still readable text; objects and lists are not
                if (value instanceof Map || value instanceof List) out.add(path + ": expected text, got " + describe(value));
            }
            case NUMBER -> {
                if (!(value instanceof Number)) out.add(path + ": expected a number, got " + describe(value));
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) out.add(path + ": expected true or false, got " + describe(value));
            }
            default -> { }
        }
    }

    /** The JSON text inside an answer: a fenced block, or the first complete object or list, found by balancing brackets outside strings. */
    static String extract(String raw, SchemaDef schema) {
        String text = raw == null ? "" : raw.strip();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int bodyStart = text.indexOf('\n', fence);
            int close = bodyStart < 0 ? -1 : text.indexOf("```", bodyStart);
            if (bodyStart >= 0) text = (close < 0 ? text.substring(bodyStart + 1) : text.substring(bodyStart + 1, close)).strip();
        }
        boolean wantsList = schema != null && schema.getType() == SchemaDef.Type.LIST;
        boolean wantsObject = schema == null || schema.getType() == SchemaDef.Type.OBJECT;
        if (!wantsList && !wantsObject) return text;
        char open = wantsList ? '[' : '{';
        int start = text.indexOf(open);
        if (start < 0) throw new SchemaViolation(List.of("the answer is not JSON: it has no " + (wantsList ? "list" : "object") + " in it (it starts: " + clip(text, 60) + ")"));
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                if (--depth == 0) return text.substring(start, i + 1);
            }
        }
        throw new SchemaViolation(List.of("the JSON is not finished (the answer may have been cut off at the output limit)"));
    }

    private static String describe(Object value) {
        if (value instanceof Map) return "an object";
        if (value instanceof List) return "a list";
        if (value instanceof Number) return "the number " + value;
        if (value instanceof Boolean) return "the boolean " + value;
        return "\"" + clip(String.valueOf(value), 40) + "\"";
    }

    private static String firstLine(String message) {
        if (message == null) return "unreadable";
        String line = message.lines().findFirst().orElse(message);
        return clip(line, 120);
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
