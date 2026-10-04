package io.github.llm4j.agent.task;

import java.math.BigDecimal;
import java.util.Map;

/**
 * What a {@link Task} is given: the arguments the script passed, a read-only copy of the workflow's variables, and where in the run it is.
 *
 * <p>Everything here is an immutable copy. A task cannot change workflow state behind the runtime's back (the run could no longer be
 * replayed); it returns a {@link TaskResult} and the runtime binds it.
 */
public interface TaskContext {

    /** The named arguments of the {@code run} statement, resolved. Unmodifiable. */
    Map<String, Object> args();

    /** The workflow's variables as they were when the step started. Unmodifiable deep copy. */
    Map<String, Object> variables();

    /** The step's id in the run, such as {@code Main/s2}; empty outside a Loom run. */
    String stepId();

    /**
     * A key that stays the same when this step is retried or the run is resumed, and differs between runs. Pass it to a payment or
     * ticketing API as its idempotency key so a repeated call is recognised. Empty outside a Loom run.
     */
    String idempotencyKey();

    /** An argument, or null when absent. */
    default Object arg(String name) {
        return args().get(name);
    }

    /** An argument converted to {@code type} (numbers and numeric strings convert between number types); null when absent. */
    default <T> T arg(String name, Class<T> type) {
        Object v = args().get(name);
        return v == null ? null : convert(v, type, "argument \"" + name + "\"");
    }

    /** An argument that must be present, else {@link TaskNotPerformed}. */
    default Object requireArg(String name) {
        Object v = args().get(name);
        if (v == null) throw new TaskNotPerformed("argument \"" + name + "\" is required");
        return v;
    }

    /** A required argument converted to {@code type}, else {@link TaskNotPerformed}. */
    default <T> T requireArg(String name, Class<T> type) {
        return convert(requireArg(name), type, "argument \"" + name + "\"");
    }

    /** A workflow variable by dotted path ({@code request.order_id}, {@code items.0.sku}); null when any step of the path is missing. */
    default Object variable(String path) {
        Object current = variables();
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(part);
            } else if (current instanceof java.util.List<?> l && part.matches("\\d+")) {
                int i = Integer.parseInt(part);
                current = i < l.size() ? l.get(i) : null;
            } else {
                return null;
            }
            if (current == null) return null;
        }
        return current;
    }

    /** A workflow variable converted to {@code type}; null when absent. */
    default <T> T variable(String path, Class<T> type) {
        Object v = variable(path);
        return v == null ? null : convert(v, type, "variable \"" + path + "\"");
    }

    /** A context for running a task outside Loom (a plain Java caller, a unit test). */
    static TaskContext of(Map<String, ?> args, Map<String, ?> variables) {
        return of(args, variables, "", "");
    }

    /** A context with a step id and idempotency key. Inputs are copied. */
    static TaskContext of(Map<String, ?> args, Map<String, ?> variables, String stepId, String idempotencyKey) {
        return new SimpleTaskContext(TaskValues.freezeMap(args), TaskValues.freezeMap(variables),
                stepId == null ? "" : stepId, idempotencyKey == null ? "" : idempotencyKey);
    }

    /** Converts a value for the typed accessors; {@link TaskNotPerformed} when it cannot be. */
    @SuppressWarnings("unchecked")
    static <T> T convert(Object value, Class<T> type, String what) {
        if (type.isInstance(value)) return (T) value;
        try {
            if (type == String.class) return (T) String.valueOf(value);
            if (type == Boolean.class) {
                String s = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
                if (s.equals("true")) return (T) Boolean.TRUE;
                if (s.equals("false")) return (T) Boolean.FALSE;
                throw new NumberFormatException("not true or false");
            }
            if (Number.class.isAssignableFrom(type)) {
                BigDecimal number = value instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(String.valueOf(value).trim());
                if (type == BigDecimal.class) return (T) number;
                if (type == Double.class) return (T) Double.valueOf(number.doubleValue());
                if (type == Float.class) return (T) Float.valueOf(number.floatValue());
                if (type == Long.class) return (T) Long.valueOf(number.longValueExact());
                if (type == Integer.class) return (T) Integer.valueOf(number.intValueExact());
                if (type == Short.class) return (T) Short.valueOf(number.shortValueExact());
            }
        } catch (RuntimeException e) {
            throw new TaskNotPerformed(what + " is " + describe(value) + ", which is not a usable " + type.getSimpleName(), e);
        }
        throw new TaskNotPerformed(what + " is " + describe(value) + ", which is not a " + type.getSimpleName());
    }

    private static String describe(Object value) {
        String s = String.valueOf(value);
        return "\"" + (s.length() > 40 ? s.substring(0, 40) + "..." : s) + "\"";
    }
}
