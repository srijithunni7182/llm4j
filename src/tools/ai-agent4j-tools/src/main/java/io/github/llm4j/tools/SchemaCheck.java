package io.github.llm4j.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Checks a JSON document against a JSON Schema, for {@code verify}. Only the keywords below are
 * known; a schema that uses any other is refused as a whole, because a check that silently skips a
 * rule would pass what it never looked at. Every place the document does not fit is reported, with
 * its JSON Pointer.
 */
final class SchemaCheck {

    /** How deep a schema or a document may nest; the JSON reader refuses anything deeper. */
    static final int MAX_DEPTH = 64;

    /** The keywords that are checked, and those that only describe. */
    static final Set<String> KNOWN =
            Set.of(
                    "type",
                    "properties",
                    "required",
                    "additionalProperties",
                    "items",
                    "enum",
                    "const",
                    "minimum",
                    "maximum",
                    "exclusiveMinimum",
                    "exclusiveMaximum",
                    "minLength",
                    "maxLength",
                    "minItems",
                    "maxItems",
                    "$schema",
                    "$id",
                    "$comment",
                    "title",
                    "description",
                    "default",
                    "examples");

    private SchemaCheck() {}

    /**
     * What is wrong with the schema itself, or null. Its depth, like a document's, is bounded when
     * it is read ({@link #MAX_DEPTH}), which also bounds the recursion here.
     */
    static String problem(JsonNode schema) {
        return problem(schema, "");
    }

    private static String problem(JsonNode schema, String where) {
        if (schema.isBoolean()) return null;
        if (!schema.isObject()) return "the schema" + at(where) + " is not an object";
        for (Iterator<String> it = schema.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!KNOWN.contains(k))
                return "the keyword "
                        + k
                        + at(where)
                        + " is not supported by verify (it supports type, properties, required,"
                        + " additionalProperties, items, enum, const, minimum, maximum,"
                        + " exclusiveMinimum, exclusiveMaximum, minLength, maxLength, minItems,"
                        + " maxItems)";
        }
        JsonNode props = schema.get("properties");
        if (props != null) {
            if (!props.isObject()) return "properties" + at(where) + " is not an object";
            for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String p = problem(e.getValue(), where + "/properties/" + e.getKey());
                if (p != null) return p;
            }
        }
        for (String sub : new String[] {"items", "additionalProperties"}) {
            JsonNode s = schema.get(sub);
            if (s != null) {
                String p = problem(s, where + "/" + sub);
                if (p != null) return p;
            }
        }
        return null;
    }

    private static String at(String where) {
        return where.isEmpty() ? "" : " at " + where;
    }

    /**
     * Adds a violation to {@code verdict} for every place {@code doc} does not fit {@code schema}.
     */
    static void check(JsonNode schema, JsonNode doc, String path, Verdict verdict) {
        if (schema.isBoolean()) {
            if (!schema.asBoolean()) verdict.violation(name(path), "no value is allowed here");
            return;
        }
        JsonNode type = schema.get("type");
        if (type != null && !typeFits(type, doc)) {
            verdict.violation(name(path), "must be " + types(type) + ", not " + typeOf(doc));
            return;
        }
        JsonNode values = schema.get("enum");
        if (values != null && !contains(values, doc))
            verdict.violation(name(path), "must be one of " + values);
        JsonNode constant = schema.get("const");
        if (constant != null && !same(constant, doc))
            verdict.violation(name(path), "must be " + constant);
        if (doc.isNumber()) numbers(schema, doc.decimalValue(), path, verdict);
        if (doc.isTextual())
            lengths(
                    schema,
                    doc.asText().codePointCount(0, doc.asText().length()),
                    "characters",
                    "Length",
                    path,
                    verdict);
        if (doc.isArray()) {
            lengths(schema, doc.size(), "items", "Items", path, verdict);
            JsonNode items = schema.get("items");
            if (items != null)
                for (int i = 0; i < doc.size(); i++)
                    check(items, doc.get(i), path + "/" + i, verdict);
        }
        if (doc.isObject()) objects(schema, doc, path, verdict);
    }

    private static void objects(JsonNode schema, JsonNode doc, String path, Verdict verdict) {
        JsonNode required = schema.get("required");
        if (required != null)
            for (JsonNode r : required)
                if (!doc.has(r.asText()))
                    verdict.violation(name(path), r.asText() + " is required");
        JsonNode props = schema.get("properties");
        JsonNode extra = schema.get("additionalProperties");
        for (Iterator<Map.Entry<String, JsonNode>> it = doc.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String child = path + "/" + e.getKey();
            JsonNode own = props == null ? null : props.get(e.getKey());
            if (own != null) check(own, e.getValue(), child, verdict);
            else if (extra != null) {
                if (extra.isBoolean() && !extra.asBoolean())
                    verdict.violation(child, "is not a property the schema allows");
                else if (!extra.isBoolean()) check(extra, e.getValue(), child, verdict);
            }
        }
    }

    private static void numbers(JsonNode schema, BigDecimal n, String path, Verdict verdict) {
        if (below(schema, "minimum", n, false))
            verdict.violation(name(path), "must be at least " + schema.get("minimum"));
        if (above(schema, "maximum", n, false))
            verdict.violation(name(path), "must be at most " + schema.get("maximum"));
        if (below(schema, "exclusiveMinimum", n, true))
            verdict.violation(name(path), "must be more than " + schema.get("exclusiveMinimum"));
        if (above(schema, "exclusiveMaximum", n, true))
            verdict.violation(name(path), "must be less than " + schema.get("exclusiveMaximum"));
    }

    private static boolean below(JsonNode schema, String key, BigDecimal n, boolean exclusive) {
        JsonNode limit = schema.get(key);
        if (limit == null || !limit.isNumber()) return false;
        int c = n.compareTo(limit.decimalValue());
        return exclusive ? c <= 0 : c < 0;
    }

    private static boolean above(JsonNode schema, String key, BigDecimal n, boolean exclusive) {
        JsonNode limit = schema.get(key);
        if (limit == null || !limit.isNumber()) return false;
        int c = n.compareTo(limit.decimalValue());
        return exclusive ? c >= 0 : c > 0;
    }

    private static void lengths(
            JsonNode schema, int size, String unit, String suffix, String path, Verdict verdict) {
        JsonNode min = schema.get("min" + suffix);
        JsonNode max = schema.get("max" + suffix);
        if (min != null && size < min.asInt())
            verdict.violation(name(path), "must have at least " + min.asInt() + " " + unit);
        if (max != null && size > max.asInt())
            verdict.violation(name(path), "must have at most " + max.asInt() + " " + unit);
    }

    private static boolean typeFits(JsonNode type, JsonNode doc) {
        if (type.isArray()) {
            for (JsonNode t : type) if (fits(t.asText(), doc)) return true;
            return false;
        }
        return fits(type.asText(), doc);
    }

    private static boolean fits(String type, JsonNode doc) {
        return switch (type) {
            case "string" -> doc.isTextual();
            case "number" -> doc.isNumber();
            case "integer" -> doc.isNumber()
                    && doc.decimalValue().stripTrailingZeros().scale() <= 0;
            case "boolean" -> doc.isBoolean();
            case "object" -> doc.isObject();
            case "array" -> doc.isArray();
            case "null" -> doc.isNull();
            default -> false;
        };
    }

    private static String types(JsonNode type) {
        if (!type.isArray()) return type.asText();
        StringBuilder out = new StringBuilder();
        for (JsonNode t : type) out.append(out.length() == 0 ? "" : " or ").append(t.asText());
        return out.toString();
    }

    private static String typeOf(JsonNode doc) {
        if (doc.isTextual()) return "a string";
        if (doc.isNumber()) return "a number";
        if (doc.isBoolean()) return "true or false";
        if (doc.isObject()) return "an object";
        if (doc.isArray()) return "an array";
        return "null";
    }

    private static boolean contains(JsonNode values, JsonNode doc) {
        for (JsonNode v : values) if (same(v, doc)) return true;
        return false;
    }

    private static boolean same(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        return a.equals(b);
    }

    private static String name(String path) {
        return path.isEmpty() ? "/" : path;
    }
}
