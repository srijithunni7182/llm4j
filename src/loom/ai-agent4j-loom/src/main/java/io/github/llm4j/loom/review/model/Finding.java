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
 * @param anchor the most stable name for the thing the finding is about (an agent's name, a
 *     workflow, kind and ordinal), empty when only the title identifies it; see {@link
 *     #fingerprint()}
 * @param options the ways the developer can resolve it, when there is more than one or a choice to
 *     make; {@code fix} is the one that {@code --fix} applies on its own
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
        String source,
        String anchor,
        List<FixOption> options) {

    /** A finding with an anchor and no options. */
    public Finding(
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
            String source,
            String anchor) {
        this(
                rule, lens, severity, where, title, why, fixHint, owasp, fix, related, source,
                anchor, List.of());
    }

    /** A finding that is identified by its rule and title alone. */
    public Finding(
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
        this(
                rule, lens, severity, where, title, why, fixHint, owasp, fix, related, source, "",
                List.of());
    }

    public Finding {
        anchor = anchor == null ? "" : anchor;
        options = options == null ? List.of() : List.copyOf(options);
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
        return new Finding(
                rule, lens, s, where, title, why, fixHint, owasp, fix, related, source, anchor,
                options);
    }

    /** The same finding noting that {@code other} describes the same cause. */
    public Finding relatedTo(String other) {
        java.util.ArrayList<String> r = new java.util.ArrayList<>(related);
        if (!r.contains(other)) r.add(other);
        return new Finding(
                rule, lens, severity, where, title, why, fixHint, owasp, fix, r, source, anchor,
                options);
    }

    /** The same finding about the named thing. */
    public Finding withAnchor(String a) {
        return new Finding(
                rule, lens, severity, where, title, why, fixHint, owasp, fix, related, source, a,
                options);
    }

    /**
     * A short stable identity: a hash of the rule, the anchor (a name when there is one, else the
     * wording of the title) and the title with its numbers taken out. Line numbers, spacing and
     * edits elsewhere in the file do not change it, so an accept or a baseline entry keeps matching
     * after the script is edited above it, and does not match a different finding that moved into
     * its place.
     */
    public String fingerprint() {
        String subject =
                title.toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("[0-9]+", "#")
                        .replaceAll("\\s+", " ")
                        .strip();
        String key = rule + "\n" + (anchor.isEmpty() ? "" : anchor) + "\n" + subject;
        try {
            byte[] d =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
