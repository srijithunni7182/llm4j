package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.review.model.Location;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing in the fact base: a kind, a stable id, where it is, and its facts. A value is a {@link
 * Boolean}, a {@link Long} or {@link Double}, a {@link String}, a list of those or of other {@code
 * Entity}s, another {@code Entity}, or {@link Unknown}. A field that was never set reads as
 * unknown, never as false.
 */
public final class Entity {

    private final String kind;
    private final String id;
    private final Location where;
    private final Map<String, Object> values = new LinkedHashMap<>();

    Entity(String kind, String id, Location where) {
        this.kind = kind;
        this.id = id;
        this.where = where;
    }

    public String kind() {
        return kind;
    }

    /** A stable id such as {@code agent:Replier} or {@code step:Main/n7}. */
    public String id() {
        return id;
    }

    public Location where() {
        return where;
    }

    /** The fact called {@code field}, or {@link Unknown#INSTANCE} when it was not set. */
    public Object get(String field) {
        Object v = values.get(field);
        return v == null ? Unknown.INSTANCE : v;
    }

    Entity set(String field, Object value) {
        values.put(field, value == null ? Unknown.INSTANCE : value);
        return this;
    }

    /** Every fact that was set. */
    public Map<String, Object> values() {
        return java.util.Collections.unmodifiableMap(values);
    }

    @Override
    public String toString() {
        return id;
    }
}
