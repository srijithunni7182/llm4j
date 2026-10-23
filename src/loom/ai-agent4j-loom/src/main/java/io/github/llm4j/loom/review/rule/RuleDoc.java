package io.github.llm4j.loom.review.rule;

import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;
import java.util.List;

/**
 * One rule, as data: what it is about, when it fires, what to say, and the examples that prove it.
 * Loaded from a rule pack; immutable.
 *
 * @param about the kind of entity the rule runs on, once per entity of that kind
 * @param when the condition under which it fires
 * @param requires settings that must be declared for the rule to be evaluated at all, as {@code
 *     setting.<key>}
 * @param fixHint plain words: what to change
 * @param fix an optional fix written as data
 * @param heuristic true for a rule that guesses from names or text; it runs only when asked
 * @param references the OWASP ids this bears on, such as {@code LLM06}
 * @param fires scripts the rule must report on
 * @param quiet scripts the rule must stay quiet on
 * @param escalations conditions under which the finding is more serious than the rule's severity
 * @param pack the pack it came from, as {@code name@version}
 */
public record RuleDoc(
        String id,
        Lens lens,
        Severity severity,
        String about,
        Condition when,
        List<String> requires,
        String title,
        String why,
        String fixHint,
        FixTemplate fix,
        boolean heuristic,
        String since,
        List<String> references,
        String explain,
        List<Example> fires,
        List<Example> quiet,
        List<Escalation> escalations,
        String pack) {

    public RuleDoc {
        requires = List.copyOf(requires);
        references = List.copyOf(references);
        fires = List.copyOf(fires);
        quiet = List.copyOf(quiet);
        escalations = List.copyOf(escalations);
    }

    /** A reason to raise the severity: when the condition holds, the finding has this severity. */
    public record Escalation(Condition when, Severity severity) {}

    /**
     * The severity of the finding on an entity: the highest of the rule's and every escalation that
     * holds.
     */
    public Severity severityFor(java.util.function.Predicate<Condition> holds) {
        Severity best = severity;
        for (Escalation e : escalations)
            if (holds.test(e.when()) && e.severity().compareTo(best) < 0) best = e.severity();
        return best;
    }

    /** The same rule recorded as coming from {@code packName}. */
    public RuleDoc inPack(String packName) {
        return new RuleDoc(
                id,
                lens,
                severity,
                about,
                when,
                requires,
                title,
                why,
                fixHint,
                fix,
                heuristic,
                since,
                references,
                explain,
                fires,
                quiet,
                escalations,
                packName);
    }
}
