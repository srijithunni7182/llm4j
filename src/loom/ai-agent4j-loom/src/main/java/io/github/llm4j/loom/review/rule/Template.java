package io.github.llm4j.loom.review.rule;

import io.github.llm4j.loom.review.fact.Entity;
import io.github.llm4j.loom.review.fact.FactBase;
import io.github.llm4j.loom.review.fact.Unknown;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills the {@code {field}} placeholders of a rule's title, reason and hint from the entity the
 * rule fired on. This is the only substitution there is: a placeholder is a path, never an
 * expression, and text that is not a path in braces (such as {@code { pii: mask }}) is left alone.
 */
public final class Template {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*)\\}");

    private Template() {}

    /** The paths a template mentions, for the validator. */
    public static List<String> placeholders(String template) {
        List<String> out = new ArrayList<>();
        if (template == null) return out;
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    public static String render(String template, Entity root, FactBase facts) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder b = new StringBuilder();
        while (m.find())
            m.appendReplacement(
                    b, Matcher.quoteReplacement(show(Evaluator.read(root, m.group(1), facts))));
        m.appendTail(b);
        return b.toString();
    }

    /**
     * A fact as words: an entity by its name, a list joined by commas, an unknown as a question
     * mark.
     */
    static String show(Object v) {
        if (v == Unknown.INSTANCE) return "?";
        if (v instanceof Entity e) {
            Object name = e.get("name");
            if (name instanceof String s) return s;
            Object id = e.get("id");
            return id instanceof String s ? s : e.id();
        }
        if (v instanceof List<?> l)
            return l.stream().map(Template::show).reduce((a, c) -> a + ", " + c).orElse("none");
        if (v instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        return String.valueOf(v);
    }
}
