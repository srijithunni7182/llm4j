package io.github.llm4j.loom.review.model;

import java.util.List;
import java.util.Optional;

/**
 * One thing a review found: the single shape every source of findings (the security audit, the
 * validator, rule packs, Java rules) is turned into.
 *
 * @param rule a stable id such as {@code LA05} or {@code LR01}; an id never changes meaning
 * @param lens what aspect of the workflow this is about
 * @param severity how much it matters
 * @param where the file, line and (when known) graph node
 * @param title one line: what is wrong
 * @param why plain words: why it matters
 * @param fixHint plain words: what to change; always present
 * @param owasp the OWASP Top 10 for LLM Applications ids this bears on, empty outside security
 * @param fix a machine-applicable change, when there is one
 * @param related ids of other findings that describe the same cause, kept out of the main list
 * @param source who produced it: {@code audit}, {@code validator} or {@code pack name@version}
 */
public record Finding(
        String rule,
        Lens lens,
        Severity severity,
        Location where,
        String title,
        String why,
        String fixHint,
        List<String> owasp,
        Optional<Fix> fix,
        List<String> related,
        String source) {

    public Finding {
        owasp = List.copyOf(owasp);
        related = List.copyOf(related);
        fix = fix == null ? Optional.empty() : fix;
        if (rule == null || rule.isBlank())
            throw new IllegalArgumentException("a finding needs a rule id");
        if (fixHint == null || fixHint.isBlank())
            throw new IllegalArgumentException("a finding needs a fix hint: " + rule);
    }

    /** The same finding with another severity (a project setting). */
    public Finding withSeverity(Severity s) {
        return new Finding(rule, lens, s, where, title, why, fixHint, owasp, fix, related, source);
    }

    /** The same finding noting that {@code other} describes the same cause. */
    public Finding relatedTo(String other) {
        java.util.ArrayList<String> r = new java.util.ArrayList<>(related);
        if (!r.contains(other)) r.add(other);
        return new Finding(rule, lens, severity, where, title, why, fixHint, owasp, fix, r, source);
    }
}
