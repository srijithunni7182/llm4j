package io.github.llm4j.loom.review.rule;

import java.util.List;

/**
 * A rule's {@code when}: a tree of conditions from a closed vocabulary. There are no variables, no
 * functions and no loops, so evaluating a condition always finishes.
 */
public sealed interface Condition {

    /** True when every condition is true. */
    record All(List<Condition> all) implements Condition {
        public All {
            all = List.copyOf(all);
        }
    }

    /** True when at least one condition is true. */
    record Any(List<Condition> any) implements Condition {
        public Any {
            any = List.copyOf(any);
        }
    }

    /** True when the condition is false; unknown stays unknown. */
    record Not(Condition not) implements Condition {}

    /**
     * One test of one fact. {@code path} is a dotted path from the rule's entity; {@code nested} is
     * set only for {@code some}, {@code every} and {@code none}, and is evaluated with each element
     * of the list as its root.
     */
    record Leaf(String path, Op op, Operand operand, Condition nested) implements Condition {}
}
