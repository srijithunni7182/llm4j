package io.github.llm4j.loom.review.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.model.Suppressed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The review as JSON, version 1: {@code {version, file, summary, findings[], suppressed[],
 * notes[]}}. New keys may be added; none is renamed or removed within a version.
 */
public final class JsonReport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonReport() {}

    public static String write(ReviewReport report) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree(report));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The report as plain maps and lists, for callers that embed it (the editor protocol). */
    public static Map<String, Object> tree(ReviewReport r) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", ReviewReport.VERSION);
        root.put("file", r.file());
        root.put("summary", summary(r));
        root.put("findings", r.findings().stream().map(JsonReport::finding).toList());
        root.put("suppressed", r.suppressed().stream().map(JsonReport::suppressed).toList());
        root.put("notes", r.notes().stream().map(JsonReport::note).toList());
        return root;
    }

    private static Map<String, Object> summary(ReviewReport r) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", r.findings().size());
        for (Severity sev : Severity.values()) s.put(sev.word(), r.count(sev));
        Map<String, Object> lenses = new LinkedHashMap<>();
        lenses.put("ran", ids(r.lensesRun()));
        lenses.put("off", ids(r.lensesOff()));
        s.put("lenses", lenses);
        return s;
    }

    private static List<String> ids(List<Lens> lenses) {
        List<String> out = new ArrayList<>();
        for (Lens l : lenses) out.add(l.id());
        return out;
    }

    private static Map<String, Object> suppressed(Suppressed s) {
        Map<String, Object> m = finding(s.finding());
        m.put("suppressedAt", s.place());
        m.put("reason", s.reason());
        return m;
    }

    private static Map<String, Object> finding(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", f.rule());
        m.put("lens", f.lens().id());
        m.put("severity", f.severity().word());
        Map<String, Object> where = new LinkedHashMap<>();
        where.put("file", f.where().file());
        where.put("line", f.where().line());
        where.put("column", f.where().column());
        where.put("endLine", f.where().endLine());
        where.put("endColumn", f.where().endColumn());
        where.put("node", f.where().nodeId());
        m.put("location", where);
        m.put("title", f.title());
        m.put("why", f.why());
        m.put("fixHint", f.fixHint());
        m.put("owasp", f.owasp());
        m.put("related", f.related());
        m.put("source", f.source());
        f.fix()
                .ifPresent(
                        fix -> {
                            Map<String, Object> x = new LinkedHashMap<>();
                            x.put("safety", fix.safety().name().toLowerCase(java.util.Locale.ROOT));
                            x.put("label", fix.label());
                            x.put(
                                    "edits",
                                    fix.edits().stream()
                                            .map(
                                                    e -> {
                                                        Map<String, Object> ed =
                                                                new LinkedHashMap<>();
                                                        ed.put("file", e.file());
                                                        ed.put("startLine", e.startLine());
                                                        ed.put("startColumn", e.startColumn());
                                                        ed.put("endLine", e.endLine());
                                                        ed.put("endColumn", e.endColumn());
                                                        ed.put("newText", e.newText());
                                                        return ed;
                                                    })
                                            .toList());
                            m.put("fix", x);
                        });
        return m;
    }

    private static Map<String, Object> note(Note n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", n.kind().name().toLowerCase(java.util.Locale.ROOT));
        m.put("rule", n.rule());
        m.put("message", n.message());
        return m;
    }
}
