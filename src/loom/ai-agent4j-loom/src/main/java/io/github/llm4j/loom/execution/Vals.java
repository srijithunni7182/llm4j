package io.github.llm4j.loom.execution;

import java.util.Map;

/** Small readers for the loosely typed maps the journal keeps. */
final class Vals {
    private Vals() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    static long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return 0;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static java.math.BigDecimal money(Object value) {
        if (value == null) return java.math.BigDecimal.ZERO;
        try {
            return new java.math.BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return java.math.BigDecimal.ZERO;
        }
    }
}
