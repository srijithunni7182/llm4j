package io.github.llm4j.loom.review.rule;

import java.util.List;

/**
 * How a condition came out, with the value each leaf saw. Kept as a tree so {@code weave rules
 * explain --at} can show why a rule did or did not fire without anyone reading engine code.
 */
public record Evaluation(Tri result, String text, List<Evaluation> children) {

    public Evaluation {
        children = List.copyOf(children);
    }

    /** The tree as indented lines. */
    public String render() {
        StringBuilder b = new StringBuilder();
        render(b, 0);
        return b.toString();
    }

    private void render(StringBuilder b, int depth) {
        b.append("  ".repeat(depth))
                .append(result.name().toLowerCase(java.util.Locale.ROOT))
                .append("  ")
                .append(text)
                .append('\n');
        for (Evaluation c : children) c.render(b, depth + 1);
    }
}
