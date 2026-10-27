package io.github.llm4j.loom.review.rule;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A rule's condition as a sentence for a person, such as {@code kind is delegate and agent.hasTools
 * is true}. Used by the generated rule pages and by the pack diff, so a change to a condition can
 * be read without reading YAML.
 */
public final class ConditionText {

    private ConditionText() {}

    public static String of(Condition c) {
        return of(c, false);
    }

    private static String of(Condition c, boolean nested) {
        if (c instanceof Condition.All a) return join(a.all(), " and ", nested);
        if (c instanceof Condition.Any a) return join(a.any(), " or ", nested);
        if (c instanceof Condition.Not n) return "not (" + of(n.not(), false) + ")";
        Condition.Leaf l = (Condition.Leaf) c;
        String path = l.path();
        if (l.op().quantifier())
            return l.op().key() + " of " + path + " where (" + of(l.nested(), false) + ")";
        return switch (l.op()) {
            case IS -> path + " is " + operand(l.operand());
            case IS_NOT -> path + " is not " + operand(l.operand());
            case IN -> path + " is one of " + operand(l.operand());
            case NOT_IN -> path + " is none of " + operand(l.operand());
            case GT -> path + " > " + operand(l.operand());
            case GTE -> path + " >= " + operand(l.operand());
            case LT -> path + " < " + operand(l.operand());
            case LTE -> path + " <= " + operand(l.operand());
            case CONTAINS -> path + " contains " + operand(l.operand());
            case STARTS_WITH -> path + " starts with " + operand(l.operand());
            case MATCHES -> path + " matches " + operand(l.operand());
            case EXISTS -> path + " is set";
            case MISSING -> path + " is not set";
            case COUNT -> {
                Operand.Count n = (Operand.Count) l.operand();
                yield "number of " + path + " " + symbol(n.comparison()) + " " + n.bound();
            }
            default -> path + " " + l.op().key();
        };
    }

    private static String join(List<Condition> parts, String word, boolean nested) {
        String s = parts.stream().map(p -> of(p, true)).collect(Collectors.joining(word));
        return nested && parts.size() > 1 ? "(" + s + ")" : s;
    }

    private static String symbol(Op op) {
        return switch (op) {
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            default -> op.key();
        };
    }

    private static String operand(Operand o) {
        if (o instanceof Operand.Literal l) return String.valueOf(l.value());
        if (o instanceof Operand.Literals l)
            return l.values().stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", ", "[", "]"));
        if (o instanceof Operand.Setting s) return "the setting " + s.key();
        if (o instanceof Operand.Field f) return "the value of " + f.path();
        return "";
    }
}
