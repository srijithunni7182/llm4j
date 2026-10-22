package io.github.llm4j.loom.review.pack;

import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.fact.Entity;
import io.github.llm4j.loom.review.fact.FactBase;
import io.github.llm4j.loom.review.fact.FactBuilder;
import io.github.llm4j.loom.review.fact.Unknown;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.rule.Evaluation;
import io.github.llm4j.loom.review.rule.Evaluator;
import io.github.llm4j.loom.review.rule.RuleDoc;
import io.github.llm4j.loom.review.rule.Template;
import io.github.llm4j.loom.review.rule.Tri;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the rules of one or more packs over the facts of a review and reports what fires. The facts
 * are built once and shared by every rule. A rule that needs something undeclared is not run and is
 * reported as not evaluated, never as passing.
 */
public final class DataRuleSource implements FindingSource {

    private final List<Pack> packs;
    private final FactBuilder facts;

    public DataRuleSource(List<Pack> packs) {
        this(packs, FactBuilder.standard());
    }

    public DataRuleSource(List<Pack> packs, FactBuilder facts) {
        this.packs = List.copyOf(packs);
        this.facts = facts;
    }

    @Override
    public String name() {
        return "rule packs";
    }

    @Override
    public void collect(ReviewInput input, ReviewSettings settings, Sink sink) {
        FactBase base = facts.build(input, settings);
        for (Pack pack : packs) for (RuleDoc rule : pack.rules()) run(rule, base, settings, sink);
    }

    private void run(RuleDoc rule, FactBase base, ReviewSettings settings, Sink sink) {
        if (!settings.lensOn(rule.lens())) return;
        Boolean enabled = settings.rule(rule.id()).enabled();
        if (Boolean.FALSE.equals(enabled)) return;
        if (rule.heuristic() && !settings.heuristics() && !Boolean.TRUE.equals(enabled)) return;
        String missing = unmet(rule, base, settings);
        if (missing != null) {
            sink.note(Note.notEvaluated(rule.id(), rule.id() + " was not evaluated: " + missing));
            return;
        }
        for (Entity e : base.entities(rule.about())) {
            Evaluation result = Evaluator.evaluate(rule.when(), e, base);
            if (result.result() != Tri.TRUE) continue;
            sink.finding(finding(rule, e, base));
        }
    }

    /** What the rule needs that is not there, as a sentence, or {@code null} when it can run. */
    private static String unmet(RuleDoc rule, FactBase base, ReviewSettings settings) {
        Set<String> needs = new HashSet<>(rule.requires());
        if (rule.about().equals("step")) needs.add("graph");
        for (String need : needs) {
            if (need.equals("graph")) {
                if (!base.graphAvailable()) return "the workflow graph could not be built";
            } else if (need.startsWith("setting.")) {
                String key = need.substring("setting.".length());
                if (base.setting(key) == Unknown.INSTANCE)
                    return key + " is not declared in review.properties";
            }
        }
        return null;
    }

    static Finding finding(RuleDoc rule, Entity e, FactBase base) {
        return new Finding(
                rule.id(),
                rule.lens(),
                rule.severity(),
                e.where(),
                Template.render(rule.title(), e, base),
                Template.render(rule.why(), e, base),
                Template.render(rule.fixHint(), e, base),
                rule.references(),
                Optional.empty(),
                List.of(),
                "pack " + rule.pack());
    }
}
