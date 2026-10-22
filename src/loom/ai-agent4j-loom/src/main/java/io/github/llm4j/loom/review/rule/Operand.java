package io.github.llm4j.loom.review.rule;

import java.util.List;

/**
 * What a leaf compares a fact with: a literal, a list of literals, a project setting, or another
 * fact of the same entity.
 */
public sealed interface Operand {

    /** One literal: a string, a number or a boolean. */
    record Literal(Object value) implements Operand {}

    /** A list of literals, for {@code in} and {@code notIn}. */
    record Literals(List<Object> values) implements Operand {
        public Literals {
            values = List.copyOf(values);
        }
    }

    /**
     * The declared value of {@code setting.<key>}; unknown when the project has not declared it.
     */
    record Setting(String key) implements Operand {}

    /** Another fact of the same entity, read by path. */
    record Field(String path) implements Operand {}

    /** A bound on the number of elements of a list: {@code count: { gt: 0 }}. */
    record Count(Op comparison, long bound) implements Operand {}

    /** No operand ({@code exists}, {@code missing}) or a nested condition (carried by the leaf). */
    record None() implements Operand {}
}
