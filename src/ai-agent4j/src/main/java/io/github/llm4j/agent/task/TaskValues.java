package io.github.llm4j.agent.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Copies and checks the values that cross the boundary between a workflow and a task. */
final class TaskValues {

    private static final int MAX_DEPTH = 64;

    private TaskValues() { }

    /**
     * An unmodifiable deep copy: maps and lists are rebuilt (so nothing the task holds aliases workflow state), scalars are shared.
     * Lenient: a value that is not JSON-like is kept as it is.
     */
    static Object freeze(Object value) {
        return freeze(value, 0);
    }

    private static Object freeze(Object value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("value is nested more than " + MAX_DEPTH + " levels deep (or refers to itself)");
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) copy.put(String.valueOf(e.getKey()), freeze(e.getValue(), depth + 1));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> l) {
            List<Object> copy = new ArrayList<>(l.size());
            for (Object o : l) copy.add(freeze(o, depth + 1));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> freezeMap(Map<String, ?> map) {
        if (map == null) return Map.of();
        return (Map<String, Object>) freeze(map, 0);
    }

    /**
     * Checks that a result value can be written to the run journal as JSON, and returns an unmodifiable deep copy of it.
     *
     * @param what where the value is, for the error message
     */
    static Object jsonSafe(Object value, String what) {
        return jsonSafe(value, what, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Object jsonSafe(Object value, String what, int depth, Set<Object> path) {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException(what + " is " + n + ", which JSON cannot hold");
            return value;
        }
        if (depth > MAX_DEPTH) throw new IllegalArgumentException(what + " is nested more than " + MAX_DEPTH + " levels deep");
        if (value instanceof Map<?, ?> m) {
            if (!path.add(value)) throw new IllegalArgumentException(what + " refers to itself");
            try {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!(e.getKey() instanceof String key)) {
                        throw new IllegalArgumentException(what + " has a map key that is not a string: " + e.getKey());
                    }
                    copy.put(key, jsonSafe(e.getValue(), what + "." + key, depth + 1, path));
                }
                return Collections.unmodifiableMap(copy);
            } finally {
                path.remove(value);
            }
        }
        if (value instanceof List<?> l) {
            if (!path.add(value)) throw new IllegalArgumentException(what + " refers to itself");
            try {
                List<Object> copy = new ArrayList<>(l.size());
                int i = 0;
                for (Object o : l) copy.add(jsonSafe(o, what + "[" + i++ + "]", depth + 1, path));
                return Collections.unmodifiableList(copy);
            } finally {
                path.remove(value);
            }
        }
        throw new IllegalArgumentException(what + " is a " + value.getClass().getName()
                + "; a task result may hold only strings, numbers, booleans, null, maps with string keys and lists");
    }
}
