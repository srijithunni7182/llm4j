package io.github.llm4j.loom.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the attribute map of a node: a key is added only when the setting is present. */
final class Attrs {

    private static final int TEXT_LIMIT = 200;

    private final Map<String, Object> values = new LinkedHashMap<>();

    static Attrs create() {
        return new Attrs();
    }

    Attrs text(String key, String value) {
        if (value != null && !value.isBlank()) {
            String safe = Redactor.mask(value);
            values.put(key, safe.length() > TEXT_LIMIT ? safe.substring(0, TEXT_LIMIT - 1) + "…" : safe);
        }
        return this;
    }

    Attrs positive(String key, long value) {
        if (value > 0) {
            values.put(key, value);
        }
        return this;
    }

    Attrs flag(String key, boolean value) {
        if (value) {
            values.put(key, Boolean.TRUE);
        }
        return this;
    }

    Attrs list(String key, List<String> value) {
        if (value != null && !value.isEmpty()) {
            values.put(key, List.copyOf(value));
        }
        return this;
    }

    Attrs map(String key, Map<String, ?> value) {
        if (value != null && !value.isEmpty()) {
            values.put(key, new LinkedHashMap<>(value));
        }
        return this;
    }

    Attrs budget(io.github.llm4j.loom.ast.BudgetDef budget) {
        return map("budget", BudgetSummary.of(budget));
    }

    Attrs put(String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
        return this;
    }

    Map<String, Object> build() {
        return values;
    }
}
