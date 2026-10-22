package io.github.llm4j.loom.review.engine;

import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import io.github.llm4j.loom.review.settings.RuleSetting;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every source of findings over a {@link ReviewInput}, applies what the project said (lenses,
 * rules turned off, severities changed), removes exact duplicates and ranks the rest. It has no
 * rules of its own: what is found comes from its {@link FindingSource}s.
 *
 * <p>Ranking is severity, then lens order, then line, then rule id, then title, so the same input
 * always gives the same list.
 */
public final class ReviewEngine {

    private static final Comparator<Finding> RANK =
            Comparator.comparing(Finding::severity)
                    .thenComparing(Finding::lens)
                    .thenComparingInt((Finding f) -> f.where().line())
                    .thenComparing(Finding::rule)
                    .thenComparing(Finding::title);

    private final List<FindingSource> sources;

    public ReviewEngine(List<FindingSource> sources) {
        this.sources = List.copyOf(sources);
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
        List<Finding> ranked = new ArrayList<>(unique.values());
        ranked.sort(RANK);
        List<Lens> run = new ArrayList<>();
        List<Lens> off = new ArrayList<>();
        for (Lens l : Lens.values()) (settings.lensOn(l) ? run : off).add(l);
        return new ReviewReport(input.file(), ranked, List.of(), notes, run, off);
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
