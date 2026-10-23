package io.github.llm4j.loom.review.engine;

import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.model.Suppressed;
import java.util.List;

/**
 * The result of a review: the findings in rank order, what was set aside, notes about the review
 * itself, and which lenses ran. This is what every report format is written from.
 *
 * @param file the script reviewed
 * @param findings open findings, most serious first
 * @param suppressed findings a project set aside, with its reason, listed so nothing is hidden
 * @param notes rules not evaluated, fixes withheld, sources that failed
 * @param lensesRun the lenses that ran, in lens order
 * @param lensesOff the lenses that did not
 */
public record ReviewReport(
        String file,
        List<Finding> findings,
        List<Suppressed> suppressed,
        List<Note> notes,
        List<Lens> lensesRun,
        List<Lens> lensesOff) {

    /** Version of the JSON this report is written as. */
    public static final int VERSION = 1;

    public ReviewReport {
        findings = List.copyOf(findings);
        suppressed = List.copyOf(suppressed);
        notes = List.copyOf(notes);
        lensesRun = List.copyOf(lensesRun);
        lensesOff = List.copyOf(lensesOff);
    }

    /** Open findings of exactly this severity. */
    public long count(Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    /** True when an open finding is at least as serious as {@code threshold}. */
    public boolean failsAt(Severity threshold) {
        return findings.stream().anyMatch(f -> f.severity().atLeast(threshold));
    }
}
