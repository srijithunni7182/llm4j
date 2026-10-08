package io.github.llm4j.loom.ast;

import java.util.List;
import java.util.Map;

/**
 * Data class representing the expected structure of an agent's output.
 * Used to enforce JSON schema and enable typed symbolic checks.
 */
public class SchemaDef {
    public enum Type {
        OBJECT, LIST, ENUM, STRING, NUMBER, BOOLEAN
    }

    private final Type type;
    private Map<String, SchemaDef> fields; // For OBJECT
    private SchemaDef elementType;         // For LIST
    private List<String> enumValues;       // For ENUM

    public SchemaDef(Type type) {
        this.type = type;
    }

    public Type getType() { return type; }

    public Map<String, SchemaDef> getFields() { return fields; }
    public void setFields(Map<String, SchemaDef> fields) { this.fields = fields; }

    public SchemaDef getElementType() { return elementType; }
    public void setElementType(SchemaDef elementType) { this.elementType = elementType; }

    public List<String> getEnumValues() { return enumValues; }
    public void setEnumValues(List<String> enumValues) { this.enumValues = enumValues; }
}
