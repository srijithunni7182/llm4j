package io.github.llm4j.loom.review.report;

import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.model.Suppressed;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** The review as a Markdown document: for a pull request, an issue or a file to keep. */
public final class MarkdownReport {

    private MarkdownReport() {}

    public static String write(ReviewReport r) {
        StringBuilder b = new StringBuilder();
        b.append("# Review of ").append(escape(r.file())).append("\n\n");
        b.append("**")
                .append(TextReport.counts(r))
                .append("** · lenses: ")
                .append(r.lensesRun().stream().map(Lens::title).collect(Collectors.joining(", ")))
                .append("\n");
        if (!r.lensesOff().isEmpty())
            b.append("\nOptional lenses off: ")
                    .append(
                            r.lensesOff().stream()
                                    .map(Lens::title)
                                    .collect(Collectors.joining(", ")))
                    .append("\n");
        Map<Severity, List<Finding>> bySeverity = new EnumMap<>(Severity.class);
        for (Finding f : r.findings())
            bySeverity.computeIfAbsent(f.severity(), s -> new java.util.ArrayList<>()).add(f);
        for (Map.Entry<Severity, List<Finding>> e : bySeverity.entrySet()) {
            b.append("\n## ")
                    .append(capitalize(e.getKey().word()))
                    .append(" (")
                    .append(e.getValue().size())
                    .append(")\n");
            for (Finding f : e.getValue()) {
                b.append("\n### `")
                        .append(f.rule())
                        .append("` ")
                        .append(escape(f.title()))
                        .append("\n\n");
                b.append("- Lens: ").append(f.lens().title()).append("\n");
                b.append("- Where: ").append(escape(f.where().file()));
                if (!f.where().wholeScript()) b.append(":").append(f.where().line());
                b.append("\n");
                if (!f.why().isBlank()) b.append("- Why: ").append(escape(f.why())).append("\n");
                b.append("- Fix: ").append(escape(f.fixHint())).append("\n");
                if (!f.owasp().isEmpty())
                    b.append("- OWASP LLM: ").append(String.join(", ", f.owasp())).append("\n");
            }
        }
        if (!r.suppressed().isEmpty()) {
            b.append("\n## Suppressed\n\n");
            for (Suppressed x : r.suppressed())
                b.append("- `")
                        .append(x.finding().rule())
                        .append("` at ")
                        .append(escape(x.place()))
                        .append(": ")
                        .append(escape(x.finding().title()))
                        .append(" (")
                        .append(escape(x.reason()))
                        .append(")\n");
        }
        if (!r.notes().isEmpty()) {
            b.append("\n## Notes\n\n");
            for (Note n : r.notes())
                b.append("- ")
                        .append(n.rule() == null ? "" : "`" + n.rule() + "` ")
                        .append(escape(n.message()))
                        .append("\n");
        }
        b.append("\n---\nAdvisory: open findings never block run, package or deploy.\n");
        return b.toString();
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** One line with control characters removed and Markdown's own markers escaped. */
    private static String escape(String s) {
        return Safe.line(s)
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
