package io.github.llm4j.loom.graph;

import io.github.llm4j.loom.ast.SchemaDef;
import java.util.Locale;
import java.util.stream.Collectors;

/** A one-line description of an {@code expecting} schema: {@code {score, notes}}, {@code list<string>}. */
final class SchemaOutline {

    private SchemaOutline() {
    }

    static String of(SchemaDef schema) {
        if (schema == null) {
            return null;
        }
        switch (schema.getType()) {
            case OBJECT:
                return schema.getFields() == null
                        ? "{}"
                        : schema.getFields().keySet().stream().collect(Collectors.joining(", ", "{", "}"));
            case LIST:
                return "list<" + (schema.getElementType() == null ? "any" : of(schema.getElementType())) + ">";
            case ENUM:
                return schema.getEnumValues() == null
                        ? "enum"
                        : schema.getEnumValues().stream().collect(Collectors.joining("|", "enum(", ")"));
            default:
                return schema.getType().name().toLowerCase(Locale.ROOT);
        }
    }
}
