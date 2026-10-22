package io.github.llm4j.loom.review.report;

import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Note;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The review as plain text for a terminal: a summary card, then the findings most serious first,
 * each with why it matters and what to do. Text from the script is shown on one line with control
 * characters removed, so a prompt cannot change what a terminal does.
 */
public final class TextReport {

    private TextReport() {}

    public static String write(ReviewReport r) {
        StringBuilder b = new StringBuilder();
        b.append(Safe.line(r.file())).append("   ").append(counts(r)).append('\n');
        b.append("Lenses   ").append(lenses(r)).append('\n');
        if (!r.findings().isEmpty()) b.append('\n');
        for (Finding f : r.findings()) {
            b.append(
                    String.format(
                            Locale.ROOT,
                            "%-6s  %-5s %-14s %s%n",
                            f.severity().word().toUpperCase(Locale.ROOT),
                            f.rule(),
                            f.lens().title(),
                            where(f)));
            b.append("        ").append(Safe.line(f.title())).append('\n');
            if (!f.why().isBlank())
                b.append("        why: ").append(Safe.line(f.why())).append('\n');
            b.append("        fix: ").append(Safe.line(f.fixHint())).append('\n');
            f.fix()
                    .ifPresent(
                            fix ->
                                    b.append("        auto-fix (")
                                            .append(fix.safety().name().toLowerCase(Locale.ROOT))
                                            .append("): ")
                                            .append(Safe.line(fix.label()))
                                            .append('\n'));
        }
        List<Note> notes = r.notes();
        if (!notes.isEmpty()) {
            b.append('\n').append("Notes\n");
            for (Note n : notes)
                b.append("  ")
                        .append(n.rule() == null ? "" : n.rule() + ": ")
                        .append(Safe.line(n.message()))
                        .append('\n');
        }
        b.append('\n').append("Advisory: open findings never block run, package or deploy.\n");
        return b.toString();
    }

    static String counts(ReviewReport r) {
        if (r.findings().isEmpty()) return "no open findings";
        return r.count(io.github.llm4j.loom.review.model.Severity.HIGH)
                + " high  "
                + r.count(io.github.llm4j.loom.review.model.Severity.MEDIUM)
                + " medium  "
                + r.count(io.github.llm4j.loom.review.model.Severity.LOW)
                + " low"
                + (r.count(io.github.llm4j.loom.review.model.Severity.INFO) > 0
                        ? "  " + r.count(io.github.llm4j.loom.review.model.Severity.INFO) + " info"
                        : "");
    }

    static String lenses(ReviewReport r) {
        String off =
                r.lensesOff().isEmpty()
                        ? ""
                        : " · " + r.lensesOff().size() + " off (weave review --lens all)";
        return r.lensesRun().size() + " on" + off;
    }

    private static String where(Finding f) {
        String file = Safe.line(f.where().file());
        return f.where().wholeScript() ? file : file + ":" + f.where().line();
    }

    /** The lens names, for tools that list them. */
    static String names(List<Lens> lenses) {
        return lenses.stream().map(Lens::title).collect(Collectors.joining(", "));
    }
}
