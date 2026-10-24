package io.github.llm4j.loom.symbols;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.ast.SchemaDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a name holds, as far as the script says. {@code unknown} is a real answer: nothing completes
 * from it.
 *
 * @param kind string, number, boolean, yesno, enum, object, list or unknown
 * @param values for an enum: the labels; for yesno: yes and no
 * @param fields for an object
 * @param of for a list: the element
 */
public record SymType(String kind, List<String> values, Map<String, SymType> fields, SymType of) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final SymType STRING = new SymType("string", List.of(), Map.of(), null);
    public static final SymType NUMBER = new SymType("number", List.of(), Map.of(), null);
    public static final SymType BOOLEAN = new SymType("boolean", List.of(), Map.of(), null);
    public static final SymType UNKNOWN = new SymType("unknown", List.of(), Map.of(), null);
    public static final SymType YESNO = new SymType("yesno", List.of("yes", "no"), Map.of(), null);

    public static SymType enumOf(List<String> values) {
        return new SymType("enum", List.copyOf(values), Map.of(), null);
    }

    public static SymType object(Map<String, SymType> fields) {
        return new SymType("object", List.of(), new LinkedHashMap<>(fields), null);
    }

    public static SymType listOf(SymType element) {
        return new SymType("list", List.of(), Map.of(), element);
    }

    /** The type an {@code output_schema} or {@code expecting} block describes. */
    public static SymType of(SchemaDef schema) {
        if (schema == null) return UNKNOWN;
        return switch (schema.getType()) {
            case STRING -> STRING;
            case NUMBER -> NUMBER;
            case BOOLEAN -> BOOLEAN;
            case ENUM -> enumOf(
                    schema.getEnumValues() == null ? List.of() : schema.getEnumValues());
            case LIST -> listOf(of(schema.getElementType()));
            case OBJECT -> {
                Map<String, SymType> fields = new LinkedHashMap<>();
                if (schema.getFields() != null)
                    schema.getFields().forEach((k, v) -> fields.put(k, of(v)));
                yield object(fields);
            }
        };
    }

    /** The type reached by a field path ({@code kind}, or {@code a.b}), or UNKNOWN. */
    public SymType field(String path) {
        SymType at = this;
        for (String part : path.split("\\.")) {
            if (part.isEmpty()) continue;
            SymType next = at.fields.get(part);
            if (next == null) return UNKNOWN;
            at = next;
        }
        return at;
    }

    ObjectNode toJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("kind", kind);
        if (!values.isEmpty()) {
            var a = n.putArray("values");
            values.forEach(a::add);
        }
        if (kind.equals("object")) {
            ObjectNode f = n.putObject("fields");
            fields.forEach((k, v) -> f.set(k, v.toJson()));
        }
        if (of != null) n.set("of", of.toJson());
        return n;
    }
}
