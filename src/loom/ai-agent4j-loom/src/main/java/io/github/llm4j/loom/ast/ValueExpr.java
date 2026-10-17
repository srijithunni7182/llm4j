package io.github.llm4j.loom.ast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A value a script writes: the right-hand side of {@code set name = value} and the operand of {@code return value}.
 * A text may use {@code {placeholders}}; a reference is a variable or {@code var.path} and keeps its type; an object holds more values.
 */
public sealed interface ValueExpr permits ValueExpr.Text, ValueExpr.Num, ValueExpr.Bool, ValueExpr.Ref, ValueExpr.Obj {

    /** A quoted string. */
    record Text(String text) implements ValueExpr { }

    /** A number, as written. */
    record Num(String literal) implements ValueExpr { }

    /** {@code true} or {@code false}. */
    record Bool(boolean value) implements ValueExpr { }

    /** A variable or a path into one ({@code request.amount}); its value keeps its type. */
    record Ref(String path) implements ValueExpr { }

    /** {@code { key: value, ... }}, in the order written. */
    record Obj(Map<String, ValueExpr> fields) implements ValueExpr {
        public Obj {
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    /** Every text and reference inside, for the readers that look for variable names (the linter). */
    default void visitTexts(Consumer<String> visit) {
        if (this instanceof Text t) visit.accept(t.text());
        else if (this instanceof Ref r) visit.accept(r.path());
        else if (this instanceof Obj o) o.fields().values().forEach(v -> v.visitTexts(visit));
    }
}
