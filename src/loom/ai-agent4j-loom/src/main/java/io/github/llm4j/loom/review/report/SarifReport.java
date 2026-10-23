package io.github.llm4j.loom.review.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.engine.RuleCatalog;
import io.github.llm4j.loom.review.engine.RuleInfo;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.model.Suppressed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The review as SARIF 2.1.0, so the same findings appear in code scanning on GitHub and in any tool
 * that reads SARIF. A finding is a result; high is an error, medium a warning, low and info a note.
 * A suppressed finding is a result carrying a suppression with its reason, so it is listed and not
 * counted. A machine-applicable fix becomes a SARIF fix.
 */
public final class SarifReport {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String SCHEMA =
            "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json";

    private SarifReport() {}

    public static String write(ReviewReport report, RuleCatalog catalog) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree(report, catalog));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> tree(ReviewReport r, RuleCatalog catalog) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", SCHEMA);
        root.put("version", "2.1.0");
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", driver(r, catalog)));
        List<Object> results = new ArrayList<>();
        for (Finding f : r.findings()) results.add(result(f, null));
        for (Suppressed s : r.suppressed()) results.add(result(s.finding(), s));
        run.put("results", results);
        root.put("runs", List.of(run));
        return root;
    }

    private static Map<String, Object> driver(ReviewReport r, RuleCatalog catalog) {
        Set<String> ids = new TreeSet<>();
        r.findings().forEach(f -> ids.add(f.rule()));
        r.suppressed().forEach(s -> ids.add(s.finding().rule()));
        List<Object> rules = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("id", id);
            RuleInfo info = catalog.find(id).orElse(null);
            if (info != null) {
                rule.put("name", info.id());
                rule.put("shortDescription", Map.of("text", info.title()));
                rule.put("fullDescription", Map.of("text", info.explain()));
                rule.put("properties", Map.of("lens", info.lens().id(), "source", info.source()));
            }
            rules.add(rule);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "weave review");
        d.put("informationUri", "https://github.com/srijithunni7182/llm4j");
        d.put("rules", rules);
        return d;
    }

    private static Map<String, Object> result(Finding f, Suppressed s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", f.rule());
        m.put("level", level(f.severity()));
        m.put("message", Map.of("text", f.title() + (f.why().isBlank() ? "" : " " + f.why())));
        Map<String, Object> physical = new LinkedHashMap<>();
        physical.put("artifactLocation", Map.of("uri", f.where().file()));
        if (!f.where().wholeScript()) {
            Map<String, Object> region = new LinkedHashMap<>();
            region.put("startLine", f.where().line());
            if (f.where().column() > 0) region.put("startColumn", f.where().column());
            if (f.where().endLine() > 0) region.put("endLine", f.where().endLine());
            if (f.where().endColumn() > 0) region.put("endColumn", f.where().endColumn());
            physical.put("region", region);
        }
        m.put("locations", List.of(Map.of("physicalLocation", physical)));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("lens", f.lens().id());
        props.put("severity", f.severity().word());
        props.put("fixHint", f.fixHint());
        props.put("source", f.source());
        if (!f.owasp().isEmpty()) props.put("owasp", f.owasp());
        if (!f.related().isEmpty()) props.put("related", f.related());
        if (f.where().nodeId() != null) props.put("node", f.where().nodeId());
        m.put("properties", props);
        f.fix().ifPresent(fix -> m.put("fixes", List.of(fix(fix))));
        if (s != null)
            m.put(
                    "suppressions",
                    List.of(
                            Map.of(
                                    "kind",
                                    "external",
                                    "status",
                                    "accepted",
                                    "justification",
                                    s.reason())));
        return m;
    }

    private static Map<String, Object> fix(io.github.llm4j.loom.review.model.Fix fix) {
        Map<String, List<Object>> byFile = new LinkedHashMap<>();
        for (var e : fix.edits()) {
            Map<String, Object> deleted = new LinkedHashMap<>();
            deleted.put("startLine", e.startLine());
            deleted.put("startColumn", e.startColumn());
            deleted.put("endLine", e.endLine());
            deleted.put("endColumn", e.endColumn());
            byFile.computeIfAbsent(e.file(), k -> new ArrayList<>())
                    .add(
                            Map.of(
                                    "deletedRegion",
                                    deleted,
                                    "insertedContent",
                                    Map.of("text", e.newText())));
        }
        List<Object> changes = new ArrayList<>();
        byFile.forEach(
                (file, replacements) ->
                        changes.add(
                                Map.of(
                                        "artifactLocation",
                                        Map.of("uri", file),
                                        "replacements",
                                        replacements)));
        return Map.of("description", Map.of("text", fix.label()), "artifactChanges", changes);
    }

    static String level(Severity s) {
        return switch (s) {
            case HIGH -> "error";
            case MEDIUM -> "warning";
            default -> "note";
        };
    }
}
