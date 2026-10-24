package io.github.llm4j.loom.review.fact;

import java.util.List;

/**
 * One fact about an entity: its name, type, a one-line description and the fact model version it
 * appeared in. For {@code ENTITY} and {@code ENTITY_LIST} fields {@code entity} names the kind of
 * entity referred to; for {@code ENUM} fields {@code values} lists the words.
 */
public record FieldDef(
        String name,
        FieldType type,
        String entity,
        List<String> values,
        String description,
        int since) {

    public FieldDef {
        values = List.copyOf(values);
    }
}
