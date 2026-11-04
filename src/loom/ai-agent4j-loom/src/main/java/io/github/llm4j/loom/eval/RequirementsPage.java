package io.github.llm4j.loom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.PolicyDef;
import io.github.llm4j.loom.policy.PolicyCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The golden dataset written as a plain page a reviewer who is not a developer can read and sign
 * off (docs/specs/28-evaluation-first, R5): per workflow, agent and node what it must do and must
 * never do; per policy rule its text and the examples that break it and the near misses; the
 * identity of what was read; and what has no requirement yet. It reads files only: no model, no
 * key, no cost. Personal data in a case is masked.
 */
public final class RequirementsPage {

    private RequirementsPage() {}

    private sealed interface Block permits Heading, Para, Bullets {}

    private record Heading(int level, String text) implements Block {}

    private record Para(String text) implements Block {}

    private record Bullets(List<String> items) implements Block {}

    /**
     * Renders the page as {@code md} (Markdown) or {@code html}. {@code mask} takes personal data
     * and secrets out of every piece of case text before it is shown (the caller passes the
     * production masker; this package does not depend on it).
     */
    public static String render(
            LoomScript script,
            Path scriptFile,
            String format,
            Clock clock,
            UnaryOperator<String> mask) {
        Objects.requireNonNull(mask, "mask");
        if (!"md".equals(format) && !"html".equals(format))
            throw new IllegalArgumentException("--format takes md or html, not " + format);
        List<Block> blocks = build(script, scriptFile, mask);
        return "html".equals(format) ? html(blocks, scriptFile) : markdown(blocks);
    }

    // ── content ──────────────────────────────────────────────────────────────────────────────

    private static List<Block> build(
            LoomScript script, Path scriptFile, UnaryOperator<String> mask) {
        Path dir = DatasetFolder.locate(scriptFile, null);
        List<Block> out = new ArrayList<>();
        out.add(new Heading(1, "Requirements: " + scriptFile.getFileName()));
        out.add(
                new Para(
                        "What this workflow must do and must never do, as examples. Read it as a list of promises: if one is wrong, change the case, not the page."));
        out.add(new Heading(2, "What was read"));
        List<String> read = new ArrayList<>();
        read.add(
                "Dataset: "
                        + (Files.isDirectory(dir) ? DatasetIdentity.of(dir).describe() : "none yet"));
        PolicyCatalog catalog = PolicyCatalog.beside(scriptFile.toAbsolutePath().getParent());
        for (PolicyDef p : script.getPolicies())
            read.add(
                    "Policy "
                            + p.getName()
                            + ": "
                            + catalog.resolve(p)
                                    .map(r -> "version " + r.version() + ", hash " + r.hash())
                                    .orElse("could not be read"));
        out.add(new Bullets(read));
        out.add(new Para(approvalLine(script, scriptFile)));

        DatasetFolder.Plan plan = DatasetFolder.plan(script, dir, null);
        for (DatasetFolder.Target t : plan.targets()) {
            out.add(new Heading(2, ("workflow".equals(t.kind()) ? "Workflow " : "Agent ") + t.name()));
            List<String> items = new ArrayList<>();
            for (EvalScenario s : t.scenarios()) items.add(sentence(s, mask));
            out.add(new Bullets(items));
        }
        for (ExtractorDef x : script.getExtractors()) extractor(x, dir, mask, out);
        policies(script, catalog, dir, mask, out);
        missing(script, dir, out);
        return out;
    }

    private static String approvalLine(LoomScript script, Path scriptFile) {
        Optional<RequirementsApprovals.Approval> a = RequirementsApprovals.latest(scriptFile);
        if (a.isEmpty()) return "Not approved yet.";
        RequirementsApprovals.Approval ap = a.get();
        boolean same = ap.identity().equals(RequirementsApprovals.identity(script, scriptFile));
        return same
                ? "Approved by " + ap.approver() + " on " + ap.date() + ": " + ap.why()
                : "Requirements changed since approved by "
                        + ap.approver()
                        + " on "
                        + ap.date()
                        + ". Read them again and approve again.";
    }

    private static String sentence(EvalScenario s, UnaryOperator<String> mask) {
        String given = s.input() != null ? s.input() : inputs(s);
        StringBuilder b = new StringBuilder("When someone gives *").append(mask.apply(given)).append("*");
        List<String> must = new ArrayList<>();
        if (s.expectedOutputContains() != null)
            must.add("must mention *" + mask.apply(s.expectedOutputContains()) + "*");
        if (s.expectedOutputNotContains() != null)
            for (String n : s.expectedOutputNotContains())
                must.add("must never contain *" + mask.apply(n) + "*");
        if (s.expectedMinWords() != null) must.add("must be at least " + s.expectedMinWords() + " words");
        if (s.expectedMaxWords() != null) must.add("must be at most " + s.expectedMaxWords() + " words");
        if (s.expectedTools() != null && !s.expectedTools().isEmpty())
            must.add("must use " + String.join(", ", s.expectedTools()));
        if (!must.isEmpty()) b.append(", the answer ").append(String.join(", ", must));
        if (s.rubric() != null)
            for (String r : s.rubric())
                b.append("; a judge must confirm that it *").append(mask.apply(r)).append("*");
        if (s.expect() != null)
            for (String e : s.expect())
                b.append("; the run must have: *").append(mask.apply(e)).append("*");
        if (must.isEmpty() && (s.rubric() == null || s.rubric().isEmpty()) && (s.expect() == null || s.expect().isEmpty()))
            b.append(", nothing is asserted yet");
        return b.append(".").toString();
    }

    private static String inputs(EvalScenario s) {
        if (s.inputs() == null || s.inputs().isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        s.inputs().forEach((k, v) -> parts.add(k + ": " + v));
        return String.join(", ", parts);
    }

    private static void extractor(ExtractorDef x, Path dir, UnaryOperator<String> mask, List<Block> out) {
        NodeCasesGate.Loaded loaded = NodeCasesGate.load(dir, x.getName());
        if (loaded.cases().isEmpty()) return;
        out.add(new Heading(2, "Extractor " + x.getName()));
        List<String> items = new ArrayList<>();
        for (JsonNode c : loaded.cases()) {
            StringBuilder b =
                    new StringBuilder("From the text *").append(mask.apply(c.path("input").asText())).append("* it must read");
            JsonNode fields = c.get("expected_fields");
            if (fields != null && fields.isObject()) {
                List<String> parts = new ArrayList<>();
                fields.fields().forEachRemaining(f -> parts.add(f.getKey() + " = " + mask.apply(f.getValue().asText())));
                if (!parts.isEmpty()) b.append(": ").append(String.join(", ", parts));
            }
            JsonNode missing = c.get("expected_missing");
            if (missing != null && missing.isArray() && missing.size() > 0) {
                List<String> names = new ArrayList<>();
                missing.forEach(m -> names.add(m.asText()));
                b.append("; must be missing: ").append(String.join(", ", names));
            }
            items.add(b.append(".").toString());
        }
        out.add(new Bullets(items));
    }

    private static void policies(
            LoomScript script, PolicyCatalog catalog, Path dir, UnaryOperator<String> mask, List<Block> out) {
        for (PolicyDef p : script.getPolicies()) {
            var resolved = catalog.resolve(p);
            if (resolved.isEmpty()) continue;
            String evaluator = p.getCheckedBy();
            List<JsonNode> cases = new ArrayList<>();
            if (evaluator != null) {
                for (JsonNode c : NodeCasesGate.load(dir, evaluator).cases())
                    if (p.getName().equals(c.path("policy").asText())) cases.add(c);
            }
            out.add(
                    new Heading(
                            2,
                            "Policy "
                                    + p.getName()
                                    + (evaluator == null ? "" : " (checked by " + evaluator + ")")));
            for (var rule : resolved.get().rules()) {
                out.add(new Heading(3, rule.id()));
                out.add(new Para(rule.text()));
                List<String> breaks = new ArrayList<>();
                List<String> clean = new ArrayList<>();
                for (JsonNode c : cases) {
                    String verdict = c.path("expected").path(rule.id()).asText("");
                    String text = mask.apply(c.path("text").asText());
                    boolean near = c.path("near_miss").asBoolean(false);
                    if ("violated".equals(verdict)) breaks.add("*" + text + "*");
                    else if ("clean".equals(verdict))
                        clean.add("*" + text + "*" + (near ? " (a near miss)" : ""));
                }
                out.add(new Para("Breaks the rule:"));
                out.add(new Bullets(breaks.isEmpty() ? List.of("no example yet") : breaks));
                out.add(new Para("Must not count as breaking it:"));
                out.add(new Bullets(clean.isEmpty() ? List.of("no example yet") : clean));
            }
        }
    }

    private static void missing(LoomScript script, Path dir, List<Block> out) {
        var coverage = GoldenDatasets.coverage(script, dir);
        List<String> none = GoldenDatasets.testableNames(script).stream().filter(n -> !coverage.covers(n)).toList();
        out.add(new Heading(2, "No requirement yet"));
        out.add(
                none.isEmpty()
                        ? new Para("Every agent and node has at least one case.")
                        : new Bullets(none.stream().map(n -> n + ": nothing says what it must do").toList()));
    }

    // ── forms ────────────────────────────────────────────────────────────────────────────────

    private static String markdown(List<Block> blocks) {
        StringBuilder b = new StringBuilder();
        for (Block block : blocks) {
            if (block instanceof Heading h) {
                b.append("#".repeat(h.level())).append(' ').append(h.text()).append("\n\n");
            } else if (block instanceof Para p) {
                b.append(p.text()).append("\n\n");
            } else if (block instanceof Bullets l) {
                l.items().forEach(i -> b.append("- ").append(i).append('\n'));
                b.append('\n');
            }
        }
        return b.toString();
    }

    private static String html(List<Block> blocks, Path scriptFile) {
        StringBuilder b =
                new StringBuilder(
                        "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Requirements: ")
                        .append(escape(String.valueOf(scriptFile.getFileName())))
                        .append("</title><style>body{font:16px/1.5 system-ui,sans-serif;max-width:46rem;margin:2rem auto;padding:0 1rem;color:#1b1b1b;background:#fff}h1,h2,h3{line-height:1.2}li{margin:.4rem 0}em{font-style:normal;font-weight:600}@media (prefers-color-scheme:dark){body{color:#eee;background:#161616}}</style></head><body>\n");
        for (Block block : blocks) {
            if (block instanceof Heading h) {
                b.append("<h").append(h.level()).append('>').append(inline(h.text())).append("</h").append(h.level()).append(">\n");
            } else if (block instanceof Para p) {
                b.append("<p>").append(inline(p.text())).append("</p>\n");
            } else if (block instanceof Bullets l) {
                b.append("<ul>\n");
                l.items().forEach(i -> b.append("<li>").append(inline(i)).append("</li>\n"));
                b.append("</ul>\n");
            }
        }
        return b.append("</body></html>\n").toString();
    }

    private static String inline(String text) {
        return escape(text).replaceAll("\\*(.+?)\\*", "<em>$1</em>");
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
