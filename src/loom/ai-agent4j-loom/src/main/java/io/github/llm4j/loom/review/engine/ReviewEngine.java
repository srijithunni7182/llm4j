package io.github.llm4j.loom.review.engine;

import io.github.llm4j.loom.review.cost.CostEstimate;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.model.Suppressed;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import io.github.llm4j.loom.review.settings.RuleSetting;
import io.github.llm4j.loom.review.settings.Suppression;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs every source of findings over a {@link ReviewInput}, applies what the project said (lenses,
 * rules turned off, severities changed), removes exact duplicates and ranks the rest. It has no
 * rules of its own: what is found comes from its {@link FindingSource}s.
 *
 * <p>Ranking is severity, then lens order, then line, then rule id, then title, so the same input
 * always gives the same list.
 */
public final class ReviewEngine {

    static final Comparator<Finding> RANK =
            Comparator.comparing(Finding::severity)
                    .thenComparing(Finding::lens)
                    .thenComparingInt((Finding f) -> f.where().line())
                    .thenComparing(Finding::rule)
                    .thenComparing(Finding::title);

    private final List<FindingSource> sources;
    private final List<Overlap> overlaps;
    private final CostEstimator estimator;

    public ReviewEngine(List<FindingSource> sources) {
        this(sources, List.of());
    }

    public ReviewEngine(List<FindingSource> sources, List<Overlap> overlaps) {
        this(sources, overlaps, null);
    }

    /** With an estimator the report carries what the workflows cost; without one it does not. */
    public ReviewEngine(
            List<FindingSource> sources, List<Overlap> overlaps, CostEstimator estimator) {
        this.sources = List.copyOf(sources);
        this.overlaps = List.copyOf(overlaps);
        this.estimator = estimator;
    }

    public ReviewReport review(ReviewInput input, ReviewSettings settings) {
        List<Finding> raw = new ArrayList<>();
        List<Note> notes = new ArrayList<>();
        FindingSource.Sink sink =
                new FindingSource.Sink() {
                    @Override
                    public void finding(Finding finding) {
                        raw.add(finding);
                    }

                    @Override
                    public void note(Note note) {
                        notes.add(note);
                    }
                };
        for (FindingSource source : sources) {
            try {
                source.collect(input, settings, sink);
            } catch (RuntimeException e) {
                notes.add(
                        new Note(
                                Note.Kind.SOURCE_FAILED,
                                null,
                                source.name() + " could not run: " + e.getMessage()));
            }
        }
        Map<String, Finding> unique = new LinkedHashMap<>();
        for (Finding f : raw) {
            Finding adjusted = adjust(f, settings);
            if (adjusted == null) continue;
            unique.putIfAbsent(identity(adjusted), adjusted);
        }
        List<Finding> merged = fold(new ArrayList<>(unique.values()));
        List<Finding> open = new ArrayList<>();
        List<Suppressed> setAside = new ArrayList<>();
        List<Finding> baselined = new ArrayList<>();
        Set<Suppression> used = new HashSet<>();
        for (Finding f : merged) {
            Suppression match = suppression(f, settings, used, notes);
            if (match != null)
                setAside.add(
                        new Suppressed(
                                f,
                                match.place().toString(),
                                match.reason(),
                                match.until() == null ? "" : match.until().toString(),
                                match.by(),
                                match.at() == null ? "" : match.at().toString()));
            else if (settings.baseline().contains(f.fingerprint())) baselined.add(f);
            else open.add(f);
        }
        for (Suppression s : settings.suppressions()) {
            if (!s.hasReason()) open.add(noReason(s));
            else if (!used.contains(s) && !s.expired(settings.today()))
                notes.add(
                        new Note(
                                Note.Kind.INFO,
                                s.rule(),
                                s.rule()
                                        + " entry for "
                                        + s.place()
                                        + " (review.properties line "
                                        + s.line()
                                        + ") matched no finding; remove it if the problem is gone."));
        }
        // a key that is not set is about the machine, not the script: listed apart
        List<Finding> setup = new ArrayList<>(open.stream().filter(Setup::is).toList());
        open.removeIf(Setup::is);
        List<Finding> ranked = open;
        ranked.sort(RANK);
        setup.sort(RANK);
        setAside.sort(Comparator.comparing((Suppressed x) -> x.finding(), RANK));
        baselined.sort(RANK);
        List<Lens> run = new ArrayList<>();
        List<Lens> off = new ArrayList<>();
        for (Lens l : Lens.values()) (settings.lensOn(l) ? run : off).add(l);
        CostEstimate cost = null;
        if (estimator != null) {
            try {
                cost = estimator.estimate(input, settings);
            } catch (RuntimeException e) {
                notes.add(
                        new Note(
                                Note.Kind.SOURCE_FAILED,
                                null,
                                "the cost estimate could not be made: " + e.getMessage()));
            }
        }
        return new ReviewReport(
                input.file(), ranked, setAside, notes, run, off, cost, baselined, setup);
    }

    /**
     * Folds a finding into the more specific one that describes the same cause at the same place:
     * it leaves the list and its rule id is added to the other's {@code related}.
     */
    private List<Finding> fold(List<Finding> all) {
        if (overlaps.isEmpty()) return all;
        List<Finding> kept = new ArrayList<>(all);
        for (Overlap o : overlaps) {
            List<Finding> folded = new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) {
                Finding keep = kept.get(i);
                if (!keep.rule().equals(o.specific())) continue;
                for (Finding general : kept) {
                    if (general.rule().equals(o.general()) && samePlace(keep, general)) {
                        folded.add(general);
                        keep = keep.relatedTo(general.rule());
                    }
                }
                kept.set(i, keep);
            }
            kept.removeAll(folded);
        }
        return kept;
    }

    private static boolean samePlace(Finding a, Finding b) {
        if (a.where().nodeId() != null && a.where().nodeId().equals(b.where().nodeId()))
            return true;
        return a.where().line() > 0 && a.where().line() == b.where().line();
    }

    /**
     * The suppression that sets {@code f} aside, or null. One without a reason sets nothing aside.
     */
    private static Suppression suppression(
            Finding f, ReviewSettings settings, Set<Suppression> used, List<Note> notes) {
        for (Suppression s : settings.suppressions()) {
            if (!s.hasReason() || !s.matches(f)) continue;
            if (s.expired(settings.today())) {
                String note =
                        s.rule()
                                + " entry for "
                                + s.place()
                                + " expired on "
                                + s.until()
                                + " (review.properties line "
                                + s.line()
                                + "), so the finding is open again.";
                if (notes.stream().noneMatch(n -> n.message().equals(note)))
                    notes.add(new Note(Note.Kind.INFO, s.rule(), note));
                continue;
            }
            used.add(s);
            return s;
        }
        return null;
    }

    /** A suppression with no reason is a finding of its own, and hides nothing. */
    private static Finding noReason(Suppression s) {
        return new Finding(
                "LV08",
                Lens.VALIDITY,
                Severity.MEDIUM,
                Location.at(ReviewSettings.FILE_NAME, s.line()),
                s.rule() + ".suppress at " + s.place() + " has no reason",
                "A suppression nobody explained cannot be reviewed later, so it hides nothing until it has a reason.",
                "Add the reason in quotes: "
                        + s.rule()
                        + ".suppress = "
                        + s.place()
                        + " \"why this is fine\"",
                List.of(),
                java.util.Optional.empty(),
                List.of(),
                "review.properties");
    }

    /**
     * Null when the project turned the finding's lens or rule off; else the finding with its
     * severity setting applied.
     */
    private static Finding adjust(Finding f, ReviewSettings settings) {
        if (!settings.lensOn(f.lens())) return null;
        RuleSetting rule = settings.rule(f.rule());
        if (Boolean.FALSE.equals(rule.enabled())) return null;
        return rule.severityOverride().map(f::withSeverity).orElse(f);
    }

    private static String identity(Finding f) {
        return f.rule()
                + "|"
                + f.where().file()
                + "|"
                + f.where().line()
                + "|"
                + f.where().nodeId()
                + "|"
                + f.title();
    }
}
