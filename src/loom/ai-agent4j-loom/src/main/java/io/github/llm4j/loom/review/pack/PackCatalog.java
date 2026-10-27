package io.github.llm4j.loom.review.pack;

import io.github.llm4j.loom.review.engine.RuleInfo;
import io.github.llm4j.loom.review.rule.RuleDoc;
import java.util.List;

/**
 * Describes the rules of a pack for {@code weave review --explain}: generated from the rule files,
 * so there is no second document to keep in step.
 */
public final class PackCatalog {

    private PackCatalog() {}

    public static List<RuleInfo> rules(Pack pack) {
        return pack.rules().stream().map(r -> info(r)).toList();
    }

    static RuleInfo info(RuleDoc r) {
        StringBuilder text = new StringBuilder();
        if (r.explain() != null && !r.explain().isBlank())
            text.append(r.explain().strip()).append("\n\n");
        if (!r.why().isBlank()) text.append("Why it matters: ").append(r.why()).append('\n');
        text.append("What to do: ").append(r.fixHint());
        if (r.heuristic())
            text.append(
                    "\nThis rule guesses from names or text, so it runs only when heuristics are on or the rule is named in review.properties.");
        if (!r.references().isEmpty())
            text.append("\nOWASP LLM Top 10: ").append(String.join(", ", r.references()));
        if (r.lens().optional())
            text.append(
                            "\nThis rule belongs to an optional lens, off until you turn it on (--lens +")
                    .append(r.lens().id())
                    .append(").");
        return new RuleInfo(
                r.id(),
                r.lens(),
                r.severity(),
                r.title().replaceAll("\\{[^}]*\\}", "…"),
                text.toString(),
                "pack " + r.pack());
    }
}
