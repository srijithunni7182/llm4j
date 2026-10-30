package io.github.llm4j.loom.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an evaluation found, as text for a person, JSON for a tool and a page for a manager. Passed,
 * failed and unjudged are always three separate counts: a line nothing judged is never counted as
 * met.
 */
public final class EvalReport {

    /**
     * @param notRun scenarios that were not run because a limit stopped the evaluation
     * @param judge what the judge did and cost, or null when none ran
     * @param settings the run's fingerprint: what it was run with (dataset, weave build, bundle)
     * @param reproduce the command that runs it again, or null
     * @param baseline how it compares with the saved baselines, as lines (empty when not asked)
     */
    public record Run(
            String script,
            boolean mock,
            List<ScenarioResult> results,
            int notRun,
            String stoppedBecause,
            Map<String, String> dimensions,
            JudgeFacts judge,
            Map<String, String> settings,
            String reproduce,
            List<String> baseline) {

        /** A run with no comparison against baselines. */
        public Run(
                String script,
                boolean mock,
                List<ScenarioResult> results,
                int notRun,
                String stoppedBecause,
                Map<String, String> dimensions,
                JudgeFacts judge,
                Map<String, String> settings,
                String reproduce) {
            this(script, mock, results, notRun, stoppedBecause, dimensions, judge, settings, reproduce, List.of());
        }

        /** A run with no judge facts, no fingerprint and no command, as before they were kept. */
        public Run(
                String script,
                boolean mock,
                List<ScenarioResult> results,
                int notRun,
                String stoppedBecause,
                Map<String, String> dimensions) {
            this(script, mock, results, notRun, stoppedBecause, dimensions, null, Map.of(), null, List.of());
        }

        public Run {
            settings = settings == null ? Map.of() : Map.copyOf(settings);
            baseline = baseline == null ? List.of() : List.copyOf(baseline);
        }

        public long count(Status s) {
            return results.stream().filter(r -> r.status() == s).count();
        }

        public BigDecimal cost() {
            BigDecimal sum = BigDecimal.ZERO;
            boolean any = false;
            for (ScenarioResult r : results) {
                if (r.cost() != null) {
                    sum = sum.add(r.cost());
                    any = true;
                }
            }
            return any ? sum : null;
        }

        /**
         * 0 when nothing failed, 1 when something did. Unjudged scenarios do not fail the run, and
         * are never counted as passes.
         */
        public int exitCode() {
            return count(Status.FAIL) > 0 || !routingDanger().isEmpty() ? 1 : 0;
        }

        /** The dangerous routing cells the run was told about ({@code --route-danger}), as written. */
        public List<String> routingDangerNamed() {
            String named = settings().get("route-danger");
            return named == null || named.isBlank() ? List.of() : List.of(named.split(","));
        }

        /** The named dangerous routing cells that have a case in them: any of these fails the run. */
        public List<String> routingDanger() {
            return RoutingMatrix.of(results()).dangerous(routingDangerNamed());
        }
    }

    /**
     * What the judge did in a run.
     *
     * @param model the judge model
     * @param calls judge calls made
     * @param validated whether the judge was checked against human scores
     */
    public record JudgeFacts(
            String model,
            long calls,
            long promptTokens,
            long completionTokens,
            BigDecimal cost,
            boolean validated) {}

    private EvalReport() {}

    public static String summary(Run run) {
        StringBuilder b = new StringBuilder();
        b.append(
                String.format(
                        "%d passed, %d failed, %d unjudged",
                        run.count(Status.PASS),
                        run.count(Status.FAIL),
                        run.count(Status.UNJUDGED)));
        if (run.notRun() > 0) b.append(", ").append(run.notRun()).append(" not run");
        BigDecimal cost = run.cost();
        b.append(
                run.mock()
                        ? "; mock run, nothing was spent and tasks that change things were described, not run"
                        : cost == null
                                ? ""
                                : "; cost $"
                                        + cost.setScale(4, java.math.RoundingMode.HALF_UP)
                                                .toPlainString());
        if (run.stoppedBecause() != null)
            b.append("\nStopped early: ").append(run.stoppedBecause());
        if (run.count(Status.UNJUDGED) > 0) {
            b.append("\nUnjudged means nothing confirmed it (")
                    .append(run.mock() ? "a mock run does not judge" : "no judge ran or it failed")
                    .append("); it is not a pass.");
        }
        return b.toString();
    }

    /** One line per scenario, then what failed or was not judged, in words. */
    public static String detail(Run run) {
        StringBuilder b = new StringBuilder();
        for (ScenarioResult r : run.results()) {
            b.append(mark(r.status()))
                    .append(' ')
                    .append(r.target())
                    .append(" · ")
                    .append(r.label())
                    .append('\n');
            if (r.error() != null) b.append("    error: ").append(r.error()).append('\n');
            for (ScenarioResult.Check c : r.checks()) {
                if (c.status() == Status.PASS) continue;
                b.append("    ")
                        .append(mark(c.status()))
                        .append(' ')
                        .append(c.kind())
                        .append(c.text().isEmpty() ? "" : ": " + c.text())
                        .append('\n');
                if (c.detail() != null) b.append("        ").append(c.detail()).append('\n');
            }
        }
        return b.toString();
    }

    public static String mark(Status s) {
        return switch (s) {
            case PASS -> "✓";
            case FAIL -> "✗";
            case UNJUDGED -> "?";
        };
    }

    public static String json(Run run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("script", run.script());
        m.put("mock", run.mock());
        m.put("passed", run.count(Status.PASS));
        m.put("failed", run.count(Status.FAIL));
        m.put("unjudged", run.count(Status.UNJUDGED));
        m.put("notRun", run.notRun());
        if (run.cost() != null) m.put("cost", run.cost().toPlainString());
        if (run.stoppedBecause() != null) m.put("stopped", run.stoppedBecause());
        if (!run.dimensions().isEmpty()) m.put("dimensions", run.dimensions());
        List<Map<String, Object>> rs = new ArrayList<>();
        for (ScenarioResult r : run.results()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("target", r.target());
            x.put("kind", r.kind());
            x.put("file", r.file());
            x.put("id", r.id());
            x.put("name", r.name());
            x.put("status", r.status().name().toLowerCase());
            if (r.error() != null) x.put("error", r.error());
            if (r.cost() != null) x.put("cost", r.cost().toPlainString());
            if (r.input() != null) x.put("input", r.input());
            if (r.output() != null) x.put("output", r.output());
            if (!r.tags().isEmpty()) x.put("tags", r.tags());
            if (!r.dimensions().isEmpty()) x.put("dimensions", r.dimensions());
            List<Map<String, Object>> agentRows = new ArrayList<>();
            for (AgentFacts a : r.agents()) {
                Map<String, Object> y = new LinkedHashMap<>();
                y.put("agent", a.agent());
                y.put("calls", a.calls());
                y.put("promptTokens", a.promptTokens());
                y.put("completionTokens", a.completionTokens());
                if (a.cost() != null) y.put("cost", a.cost().toPlainString());
                y.put("cutAnswers", a.cutAnswers());
                y.put("fallbacks", a.fallbacks());
                agentRows.add(y);
            }
            if (!agentRows.isEmpty()) x.put("agents", agentRows);
            List<Map<String, Object>> cs = new ArrayList<>();
            for (ScenarioResult.Check c : r.checks()) {
                Map<String, Object> y = new LinkedHashMap<>();
                y.put("kind", c.kind());
                y.put("text", c.text());
                y.put("status", c.status().name().toLowerCase());
                if (c.detail() != null) y.put("detail", c.detail());
                cs.add(y);
            }
            x.put("checks", cs);
            rs.add(x);
        }
        m.put("results", rs);
        Map<String, Object> sections = new LinkedHashMap<>();
        for (EvalSections.Section sec : EvalSections.of(run)) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("title", sec.title());
            one.put("lines", sec.lines());
            sections.put(sec.id(), one);
        }
        m.put("sections", sections);
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A single self-contained page: no script, no external file, every text escaped. */
    public static String html(Run run) {
        StringBuilder b = new StringBuilder();
        b.append(
                        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Evaluation: ")
                .append(esc(run.script()))
                .append("</title><style>")
                .append(
                        "body{font:15px/1.5 system-ui,sans-serif;margin:24px auto;max-width:960px;padding:0 16px;color:#1f2330;background:#fff}")
                .append(
                        "h1{font-size:22px}.sum{display:flex;gap:12px;flex-wrap:wrap;margin:12px 0}.box{border:1px solid #cfd4e3;border-radius:8px;padding:8px 14px}")
                .append(
                        ".pass{color:#1a7f37}.fail{color:#c93232}.unjudged{color:#955f00}table{border-collapse:collapse;width:100%;margin:16px 0}")
                .append(
                        "td,th{border-bottom:1px solid #e3e6ef;padding:6px 8px;text-align:left;vertical-align:top}small{color:#5a6177}")
                .append(
                        "@media (prefers-color-scheme:dark){body{background:#14161d;color:#e7e9f1}.box,td,th{border-color:#2a3040}small{color:#9aa2b7}.pass{color:#56d364}.fail{color:#ff7b72}.unjudged{color:#e3b341}}")
                .append("</style></head><body><h1>Evaluation of ")
                .append(esc(run.script()))
                .append("</h1>");
        b.append("<div class=\"sum\"><div class=\"box pass\"><b>")
                .append(run.count(Status.PASS))
                .append("</b> passed</div>")
                .append("<div class=\"box fail\"><b>")
                .append(run.count(Status.FAIL))
                .append("</b> failed</div>")
                .append("<div class=\"box unjudged\"><b>")
                .append(run.count(Status.UNJUDGED))
                .append("</b> unjudged</div>");
        if (run.notRun() > 0)
            b.append("<div class=\"box\"><b>").append(run.notRun()).append("</b> not run</div>");
        b.append("</div><p><small>")
                .append(esc(summary(run)).replace("\n", "<br>"))
                .append("</small></p>");
        for (EvalSections.Section sec : EvalSections.of(run)) {
            b.append("<section id=\"").append(sec.id()).append("\"><h2>").append(esc(sec.title())).append("</h2>");
            if (sec.id().equals("cases")) b.append(casesTable(run));
            else b.append("<pre>").append(esc(String.join("\n", sec.lines()))).append("</pre>");
            if (sec.id().equals("by-dimension")) b.append(dimensionList(run));
            b.append("</section>");
        }
        return b.append("</body></html>").toString();
    }


    /** The cases as a table: what each did, with the input and the output that was judged. */
    private static String casesTable(Run run) {
        StringBuilder b = new StringBuilder();
        b.append(
                "<table><tr><th>Result</th><th>Target</th><th>Scenario</th><th>What happened</th></tr>");
        for (ScenarioResult r : run.results()) {
            b.append("<tr><td class=\"")
                    .append(r.status().name().toLowerCase())
                    .append("\">")
                    .append(mark(r.status()))
                    .append(' ')
                    .append(r.status().name().toLowerCase())
                    .append("</td><td>")
                    .append(esc(r.target()))
                    .append("<br><small>")
                    .append(esc(r.kind()))
                    .append("</small></td><td>")
                    .append(esc(r.label()))
                    .append("</td><td>");
            if (r.error() != null) b.append("<b>error:</b> ").append(esc(r.error())).append("<br>");
            if (r.input() != null)
                b.append("<small><b>input:</b> ").append(esc(r.input())).append("</small><br>");
            if (r.output() != null)
                b.append("<small><b>output:</b> ").append(esc(r.output())).append("</small><br>");
            for (ScenarioResult.Check c : r.checks()) {
                b.append("<span class=\"")
                        .append(c.status().name().toLowerCase())
                        .append("\">")
                        .append(mark(c.status()))
                        .append("</span> ")
                        .append(esc(c.kind()));
                if (!c.text().isEmpty()) b.append(": ").append(esc(c.text()));
                if (c.detail() != null)
                    b.append("<br><small>").append(esc(c.detail())).append("</small>");
                b.append("<br>");
            }
            b.append("</td></tr>");
        }
        b.append("</table>");
        return b.toString();
    }

    private static String dimensionList(Run run) {
        StringBuilder b = new StringBuilder();
        if (!run.dimensions().isEmpty()) {
            b.append("<h2>Dimensions</h2><ul>");
            run.dimensions()
                    .forEach(
                            (k, v) ->
                                    b.append("<li><b>")
                                            .append(esc(k))
                                            .append("</b>")
                                            .append(v.isEmpty() ? "" : ": " + esc(v))
                                            .append("</li>"));
            b.append("</ul>");
        }
        return b.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
