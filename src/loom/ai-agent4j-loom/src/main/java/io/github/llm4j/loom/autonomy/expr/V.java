package io.github.llm4j.loom.autonomy.expr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A value in the ladder expression language. {@link #UNDEFINED} is a value in its own right: a
 * missing field, an outcome that has not arrived, a division by zero. It propagates, and anything
 * that is not {@code true} at the top of a rule is {@code false}, so missing data never lets an
 * agent climb.
 */
public sealed interface V {

    /** A missing or unusable value. */
    Undefined UNDEFINED = new Undefined();

    record Undefined() implements V {}

    record Bool(boolean value) implements V {}

    record Num(double value) implements V {}

    record Str(String value) implements V {}

    /** A length of time in milliseconds. */
    record Dur(long millis) implements V {}

    /** A share with the number of cases it was taken over and its 95% Wilson interval. */
    record Rat(double value, int n, double lower95, double upper95) implements V {}

    record Lst(List<V> items) implements V {
        public Lst {
            items = List.copyOf(items);
        }
    }

    /** A record with named fields: a case, or a part of one ({@code c.response}). */
    record Rec(Map<String, V> fields) implements V {
        public Rec {
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        public V get(String name) {
            V v = fields.get(name);
            return v == null ? UNDEFINED : v;
        }
    }

    /** A collection of cases, in the order they were decided. */
    record Cs(List<Rec> cases) implements V {
        public Cs {
            cases = List.copyOf(cases);
        }
    }

    /** Cases grouped by a key, in first-seen order. */
    record Gr(Map<String, Cs> groups) implements V {
        public Gr {
            groups = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(groups));
        }
    }

    static V bool(boolean b) {
        return new Bool(b);
    }

    static V num(double d) {
        return Double.isNaN(d) || Double.isInfinite(d) ? UNDEFINED : new Num(d);
    }

    static V str(String s) {
        return s == null ? UNDEFINED : new Str(s);
    }

    static V dur(long millis) {
        return new Dur(millis);
    }

    static Rec rec(Map<String, V> fields) {
        return new Rec(fields);
    }

    static Cs cases(List<Rec> cases) {
        return new Cs(cases);
    }

    /** {@code k} out of {@code n}; undefined when there are no cases. */
    static V ratio(int k, int n) {
        if (n == 0) return UNDEFINED;
        return new Rat((double) k / n, n, Wilson.lower(k, n), Wilson.upper(k, n));
    }

    default boolean isUndefined() {
        return this instanceof Undefined;
    }

    /** True only for the boolean {@code true}; undefined and every other value are not true. */
    static boolean isTrue(V v) {
        return v instanceof Bool b && b.value();
    }

    /** The number behind a number, a ratio (its value) or a duration (its milliseconds). */
    static Optional<Double> asNumber(V v) {
        if (v instanceof Num n) return Optional.of(n.value());
        if (v instanceof Rat r) return Optional.of(r.value());
        if (v instanceof Dur d) return Optional.of((double) d.millis());
        return Optional.empty();
    }
}
