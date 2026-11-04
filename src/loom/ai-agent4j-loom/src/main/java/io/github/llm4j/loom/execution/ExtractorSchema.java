package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.model.ResponseFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The JSON Schema an extractor asks a provider to enforce (docs/specs/30-native-json-output, R3).
 * It is built from the extractor's own field declarations and nothing else, and uses only what the
 * strict rules of every provider accept: every field required, an optional field written as its
 * type or {@code null}, objects closed with {@code additionalProperties: false}, and no length,
 * range or format keywords. The extractor already reads {@code null} as "not stated", so this
 * says the same thing the prompt does.
 */
final class ExtractorSchema {

    /** The most nullable fields the strictest provider accepts across a schema. */
    static final int MAX_NULLABLE_FIELDS = 16;

    /** The deepest nesting every provider accepts. */
    static final int MAX_DEPTH = 10;

    private static final int MAX_NAME = 64;

    private ExtractorSchema() {}

    /** The format to send for {@code def}. */
    static ResponseFormat of(ExtractorDef def) {
        Map<String, Object> schema = object(def.getFields(), List.copyOf(def.getOptional()));
        return ResponseFormat.jsonSchema(safeName(def.getName()), schema);
    }

    /**
     * Why this extractor is too big for the strictest provider, or empty when it fits. An extractor
     * that does not fit uses the prompt route instead.
     */
    static Optional<String> tooBigBecause(ExtractorDef def) {
        int nullable = def.getOptional().size();
        if (nullable > MAX_NULLABLE_FIELDS)
            return Optional.of(
                    nullable
                            + " optional fields, and the strictest provider accepts at most "
                            + MAX_NULLABLE_FIELDS);
        for (Map.Entry<String, SchemaDef> field : def.getFields().getFields().entrySet()) {
            if (depth(field.getValue()) > MAX_DEPTH - 1)
                return Optional.of(
                        "field "
                                + field.getKey()
                                + " is nested deeper than "
                                + MAX_DEPTH
                                + " levels");
        }
        return Optional.empty();
    }

    /** A name a provider accepts: letters, digits, underscore and hyphen, at most 64 characters. */
    static String safeName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.length() > MAX_NAME ? safe.substring(0, MAX_NAME) : safe;
    }

    private static int depth(SchemaDef def) {
        return switch (def.getType()) {
            case LIST -> 1 + depth(def.getElementType());
            case OBJECT ->
                    1
                            + def.getFields().values().stream()
                                    .mapToInt(ExtractorSchema::depth)
                                    .max()
                                    .orElse(0);
            default -> 0;
        };
    }

    private static Map<String, Object> object(SchemaDef def, List<String> optional) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, SchemaDef> field : def.getFields().entrySet()) {
            Map<String, Object> value = value(field.getValue());
            properties.put(
                    field.getKey(),
                    optional.contains(field.getKey()) ? nullable(field.getValue(), value) : value);
            required.add(field.getKey());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> value(SchemaDef def) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (def.getType()) {
            case STRING -> out.put("type", "string");
            case NUMBER -> out.put("type", "number");
            case BOOLEAN -> out.put("type", "boolean");
            case ENUM -> {
                out.put("type", "string");
                out.put("enum", List.copyOf(def.getEnumValues()));
            }
            case LIST -> {
                out.put("type", "array");
                out.put("items", value(def.getElementType()));
            }
            case OBJECT -> out.putAll(object(def, List.of()));
        }
        return out;
    }

    /** The same value, or null: a type list for a scalar, {@code anyOf} for an object or list. */
    private static Map<String, Object> nullable(SchemaDef def, Map<String, Object> value) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (def.getType()) {
            case STRING, NUMBER, BOOLEAN -> out.put("type", List.of(value.get("type"), "null"));
            case ENUM -> {
                out.put("type", List.of("string", "null"));
                List<Object> words = new ArrayList<>(def.getEnumValues());
                words.add(null);
                out.put("enum", Arrays.asList(words.toArray()));
            }
            case LIST, OBJECT -> out.put("anyOf", List.of(value, Map.of("type", "null")));
        }
        return out;
    }
}
