package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.SchemaDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The one-line outline of an {@code expecting} schema shown as a chip. */
class SchemaOutlineTest {

    @Test
    void anObjectListsItsFields() {
        SchemaDef object = new SchemaDef(SchemaDef.Type.OBJECT);
        Map<String, SchemaDef> fields = new LinkedHashMap<>();
        fields.put("score", new SchemaDef(SchemaDef.Type.NUMBER));
        fields.put("notes", new SchemaDef(SchemaDef.Type.STRING));
        object.setFields(fields);

        assertThat(SchemaOutline.of(object)).isEqualTo("{score, notes}");
    }

    @Test
    void aListNamesItsElementAndAnEnumItsValues() {
        SchemaDef list = new SchemaDef(SchemaDef.Type.LIST);
        list.setElementType(new SchemaDef(SchemaDef.Type.STRING));
        SchemaDef choice = new SchemaDef(SchemaDef.Type.ENUM);
        choice.setEnumValues(List.of("yes", "no"));

        assertThat(SchemaOutline.of(list)).isEqualTo("list<string>");
        assertThat(SchemaOutline.of(choice)).isEqualTo("enum(yes|no)");
    }

    @Test
    void aScalarIsItsLowerCaseTypeAndNothingIsNull() {
        assertThat(SchemaOutline.of(new SchemaDef(SchemaDef.Type.BOOLEAN))).isEqualTo("boolean");
        assertThat(SchemaOutline.of(null)).isNull();
    }

    @Test
    void anObjectWithoutFieldsAListWithoutAnElementAndAnEnumWithoutValuesStillGetAnOutline() {
        assertThat(SchemaOutline.of(new SchemaDef(SchemaDef.Type.OBJECT))).isEqualTo("{}");
        assertThat(SchemaOutline.of(new SchemaDef(SchemaDef.Type.LIST))).isEqualTo("list<any>");
        assertThat(SchemaOutline.of(new SchemaDef(SchemaDef.Type.ENUM))).isEqualTo("enum");
    }
}
