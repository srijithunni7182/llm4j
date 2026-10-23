package io.github.llm4j.loom.review.pack;

import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.fact.Entity;
import io.github.llm4j.loom.review.fact.FactBase;
import io.github.llm4j.loom.review.fact.FactBuilder;
import io.github.llm4j.loom.review.fact.Unknown;
import io.github.llm4j.loom.review.fix.FixBuilder;
import io.github.llm4j.loom.review.fix.SourceText;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Fix;
import io.github.llm4j.loom.review.model.FixOption;
import io.github.llm4j.loom.review.model.FixSafety;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.rule.Evaluation;
import io.github.llm4j.loom.review.rule.Evaluator;
import io.github.llm4j.loom.review.rule.OptionTemplate;
import io.github.llm4j.loom.review.rule.RuleDoc;
import io.github.llm4j.loom.review.rule.Template;
import io.github.llm4j.loom.review.rule.Tri;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

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
        Supplier<SourceText> source = source(input);
        for (Pack pack : packs)
            for (RuleDoc rule : pack.rules()) run(rule, base, settings, sink, source, input.file());
    }

    private void run(
            RuleDoc rule,
            FactBase base,
            ReviewSettings settings,
            Sink sink,
            Supplier<SourceText> source,
            String file) {
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
            sink.finding(finding(rule, e, base, source, file));
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

    static Finding finding(
            RuleDoc rule, Entity e, FactBase base, Supplier<SourceText> source, String file) {
        Optional<Fix> fix = Optional.empty();
        SourceText text = rule.fix() == null ? null : source.get();
        if (text != null) {
            fix = FixBuilder.build(rule.fix(), e, base, text, file);
            // a fix that is usually safe may need judgement here (retrying a step that sends mail)
            if (fix.isPresent()
                    && rule.fix().reviewWhen() != null
                    && Evaluator.evaluate(rule.fix().reviewWhen(), e, base).result() == Tri.TRUE)
                fix = Optional.of(new Fix(FixSafety.REVIEW, fix.get().label(), fix.get().edits()));
        }
        List<FixOption> options = new ArrayList<>();
        SourceText optionText = rule.options().isEmpty() ? null : source.get();
        if (optionText != null)
            for (OptionTemplate o : rule.options())
                FixBuilder.buildOption(o, e, base, optionText, file).ifPresent(options::add);
        return new Finding(
                rule.id(),
                rule.lens(),
                rule.severityFor(c -> Evaluator.evaluate(c, e, base).result() == Tri.TRUE),
                e.where(),
                Template.render(rule.title(), e, base),
                Template.render(rule.why(), e, base),
                Template.render(rule.fixHint(), e, base),
                rule.references(),
                fix,
                List.of(),
                "pack " + rule.pack(),
                anchor(e, base),
                options);
    }

    /** The script's text, read once when the first fix needs it; null when it cannot be read. */
    private static Supplier<SourceText> source(ReviewInput input) {
        SourceText[] cache = new SourceText[1];
        boolean[] tried = {false};
        return () -> {
            if (!tried[0]) {
                tried[0] = true;
                try {
                    cache[0] =
                            SourceText.of(
                                    java.nio.file.Files.readString(
                                            input.baseDir().resolve(input.file())));
                } catch (java.io.IOException | RuntimeException e) {
                    cache[0] = null;
                }
            }
            return cache[0];
        };
    }

    /**
     * What the finding is about, by name where there is one: {@code agent:Replier}, or for a step
     * its workflow, kind and ordinal among steps of that kind ({@code step:Main/delegate#2}), which
     * does not change when something else is edited.
     */
    static String anchor(Entity e, FactBase base) {
        if (!e.kind().equals("step")) return e.id();
        Object workflow = e.get("workflow");
        String wf = workflow instanceof Entity w ? String.valueOf(w.get("name")) : "";
        Object kind = e.get("kind");
        int ordinal = 0;
        for (Entity other : base.entities("step")) {
            if (other.get("workflow") == workflow && other.get("kind").equals(kind)) ordinal++;
            if (other == e) break;
        }
        return "step:" + wf + "/" + kind + "#" + ordinal;
    }
}
