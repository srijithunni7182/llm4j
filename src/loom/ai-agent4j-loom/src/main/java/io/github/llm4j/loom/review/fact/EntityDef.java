package io.github.llm4j.loom.review.fact;

import java.util.List;
import java.util.Optional;

/** A kind of thing the fact model knows about (a step, an agent, a tool …) and its fields. */
public record EntityDef(String kind, String description, List<FieldDef> fields) {

    public EntityDef {
        fields = List.copyOf(fields);
    }

    public Optional<FieldDef> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }
}
