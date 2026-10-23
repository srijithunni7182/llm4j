package io.github.llm4j.loom.review.pack;

import io.github.llm4j.loom.review.rule.ConditionText;
import io.github.llm4j.loom.review.rule.RuleDoc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What changed between two versions of a rule pack, and the smallest version bump that change needs
 * (R74): a removed rule, or a different fact model, needs a major version; a new rule or a changed
 * condition, severity, lens, fix or option needs a minor one; wording and examples alone need a
 * patch.
 */
public final class PackDiff {

    /** The size of a version change. */
    public enum Bump {
        NONE,
        PATCH,
        MINOR,
        MAJOR
    }

    /** One rule that is in both versions and differs, with what differs. */
    public record Changed(String id, List<String> behaviour, List<String> wording) {}

    private final String older;
    private final String newer;
    private final List<String> added;
    private final List<String> removed;
    private final List<Changed> changed;
    private final boolean factModelChanged;
    private final Bump required;
    private final Bump declared;

    private PackDiff(
            Pack from,
            Pack to,
            List<String> added,
            List<String> removed,
            List<Changed> changed,
            boolean factModelChanged) {
        this.older = from.label();
        this.newer = to.label();
        this.added = added;
        this.removed = removed;
        this.changed = changed;
        this.factModelChanged = factModelChanged;
        Bump need = Bump.NONE;
        if (!removed.isEmpty() || factModelChanged) need = Bump.MAJOR;
        else if (!added.isEmpty() || changed.stream().anyMatch(c -> !c.behaviour().isEmpty()))
            need = Bump.MINOR;
        else if (!changed.isEmpty()) need = Bump.PATCH;
        this.required = need;
        this.declared = declared(from.manifest().version(), to.manifest().version());
    }

    public static PackDiff of(Pack from, Pack to) {
        Map<String, RuleDoc> a = byId(from);
        Map<String, RuleDoc> b = byId(to);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<Changed> changed = new ArrayList<>();
        for (String id : b.keySet()) if (!a.containsKey(id)) added.add(id);
        for (String id : a.keySet()) if (!b.containsKey(id)) removed.add(id);
        for (var e : a.entrySet()) {
            RuleDoc x = e.getValue();
            RuleDoc y = b.get(e.getKey());
            if (y == null) continue;
            List<String> behaviour = new ArrayList<>();
            List<String> wording = new ArrayList<>();
            if (!Objects.equals(x.lens(), y.lens()))
                behaviour.add("lens " + x.lens().id() + " -> " + y.lens().id());
            if (!Objects.equals(x.severity(), y.severity()))
                behaviour.add("severity " + x.severity().word() + " -> " + y.severity().word());
            if (!Objects.equals(x.about(), y.about()))
                behaviour.add("applies to " + x.about() + " -> " + y.about());
            if (!Objects.equals(x.when(), y.when()))
                behaviour.add(
                        "fires when: "
                                + ConditionText.of(x.when())
                                + "  ->  "
                                + ConditionText.of(y.when()));
            if (!Objects.equals(x.requires(), y.requires()))
                behaviour.add("requires " + x.requires() + " -> " + y.requires());
            if (x.heuristic() != y.heuristic())
                behaviour.add("heuristic " + x.heuristic() + " -> " + y.heuristic());
            if (!Objects.equals(x.escalations(), y.escalations()))
                behaviour.add("escalations changed");
            if (!Objects.equals(x.fix(), y.fix())) behaviour.add("fix changed");
            if (!Objects.equals(x.options(), y.options())) behaviour.add("options changed");
            if (!Objects.equals(x.title(), y.title())) wording.add("title");
            if (!Objects.equals(x.why(), y.why())) wording.add("why");
            if (!Objects.equals(x.fixHint(), y.fixHint())) wording.add("fix hint");
            if (!Objects.equals(x.explain(), y.explain())) wording.add("explanation");
            if (!Objects.equals(x.references(), y.references())) wording.add("references");
            if (!Objects.equals(x.fires(), y.fires()) || !Objects.equals(x.quiet(), y.quiet()))
                wording.add("examples");
            if (!behaviour.isEmpty() || !wording.isEmpty())
                changed.add(new Changed(e.getKey(), behaviour, wording));
        }
        return new PackDiff(
                from,
                to,
                added,
                removed,
                changed,
                from.manifest().factModel() != to.manifest().factModel());
    }

    private static Map<String, RuleDoc> byId(Pack p) {
        Map<String, RuleDoc> m = new LinkedHashMap<>();
        for (RuleDoc r : p.rules()) m.put(r.id(), r);
        return m;
    }

    // ---- versions

    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*");

    /** The size of the step from one version to the next; NONE when it is not a step forward. */
    static Bump declared(String from, String to) {
        Matcher a = SEMVER.matcher(from);
        Matcher b = SEMVER.matcher(to);
        if (!a.matches() || !b.matches()) return Bump.NONE;
        long[] x = parts(a);
        long[] y = parts(b);
        if (y[0] > x[0]) return Bump.MAJOR;
        if (y[0] < x[0]) return Bump.NONE;
        if (y[1] > x[1]) return Bump.MINOR;
        if (y[1] < x[1]) return Bump.NONE;
        return y[2] > x[2] ? Bump.PATCH : Bump.NONE;
    }

    private static long[] parts(Matcher m) {
        return new long[] {
            Long.parseLong(m.group(1)),
            Long.parseLong(m.group(2)),
            m.group(3) == null ? 0 : Long.parseLong(m.group(3))
        };
    }

    // ---- reading

    public String older() {
        return older;
    }

    public String newer() {
        return newer;
    }

    public List<String> added() {
        return added;
    }

    public List<String> removed() {
        return removed;
    }

    public List<Changed> changed() {
        return changed;
    }

    public boolean factModelChanged() {
        return factModelChanged;
    }

    /** The smallest bump the change needs. */
    public Bump required() {
        return required;
    }

    /** The bump the new version declares. */
    public Bump declared() {
        return declared;
    }

    /** True when the new version is not a big enough step for the change. */
    public boolean versionTooSmall() {
        return required.compareTo(declared) > 0;
    }

    public boolean unchanged() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty() && !factModelChanged;
    }

    /** The comparison as a changelog entry. */
    public String markdown() {
        StringBuilder b = new StringBuilder();
        b.append("## ").append(newer).append(" (from ").append(older).append(")\n\n");
        if (unchanged()) return b.append("No rule changed.\n").toString();
        if (factModelChanged) b.append("- The fact model version changed.\n");
        for (String id : added) b.append("- Added ").append(id).append('\n');
        for (String id : removed) b.append("- Removed ").append(id).append('\n');
        for (Changed c : changed) {
            for (String s : c.behaviour())
                b.append("- ").append(c.id()).append(": ").append(s).append('\n');
            if (!c.wording().isEmpty())
                b.append("- ")
                        .append(c.id())
                        .append(": wording changed (")
                        .append(String.join(", ", c.wording()))
                        .append(")\n");
        }
        return b.toString();
    }
}
