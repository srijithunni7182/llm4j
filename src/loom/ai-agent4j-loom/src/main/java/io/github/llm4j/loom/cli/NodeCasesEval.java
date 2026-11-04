package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.EvaluatorDef;
import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.PolicyDef;
import io.github.llm4j.loom.eval.AgentFacts;
import io.github.llm4j.loom.eval.NodeCasesGate;
import io.github.llm4j.loom.eval.ScenarioResult;
import io.github.llm4j.loom.eval.Status;
import io.github.llm4j.loom.autonomy.expr.Wilson;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.NodeCaseRunner;
import io.github.llm4j.loom.policy.PolicyCatalog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongBinaryOperator;

/**
 * Runs the golden cases of extractors and evaluators for {@code weave eval} (docs/specs/28, R10 and
 * R11) and turns them into rows of the report. An extractor case is an exact field check, with no
 * judge. An evaluator case is checked rule by rule, and the share of clean and near-miss cases it
 * flags (violated or unsure) is its false-alarm rate: above the evaluator's limit the run fails. A
 * mock run asks no model, so its node cases are unjudged and no rate is claimed.
 */
final class NodeCasesEval {

    /** What the node cases produced: result rows, the cases not reached, and why it stopped. */
    record Outcome(List<ScenarioResult> results, int notRun, String stopped) {}

    private NodeCasesEval() {}

    static Outcome run(
            LoomScript script,
            Path scriptFile,
            Path dir,
            LLMClientFactory models,
            boolean mock,
            Set<String> only,
            BooleanSupplier exhausted,
            LongBinaryOperator addSpend,
            String alreadyStopped) {
        List<ScenarioResult> results = new ArrayList<>();
        int notRun = 0;
        String stopped = alreadyStopped;
        PolicyCatalog catalog = PolicyCatalog.beside(scriptFile.toAbsolutePath().getParent());
        for (ExtractorDef x : script.getExtractors()) {
            if (only != null && !only.contains(x.getName())) continue;
            for (JsonNode c : NodeCasesGate.cases(dir, x.getName())) {
                if (stopped != null || exhausted.getAsBoolean()) {
                    if (stopped == null) stopped = "a limit was reached";
                    notRun++;
                    continue;
                }
                results.add(extractorCase(x, c, models, mock, addSpend));
            }
        }
        for (EvaluatorDef e : script.getEvaluators()) {
            if (only != null && !only.contains(e.getName())) continue;
            List<JsonNode> cases = NodeCasesGate.cases(dir, e.getName());
            int flagged = 0;
            int cleanPairs = 0;
            Map<String, int[]> perRule = new LinkedHashMap<>();
            boolean reached = true;
            for (JsonNode c : cases) {
                if (stopped != null || exhausted.getAsBoolean()) {
                    if (stopped == null) stopped = "a limit was reached";
                    notRun++;
                    reached = false;
                    continue;
                }
                ScenarioResult r = evaluatorCase(script, e, c, catalog, models, mock, addSpend, perRule);
                results.add(r);
            }
            for (int[] counts : perRule.values()) {
                cleanPairs += counts[0];
                flagged += counts[1];
            }
            if (!mock && reached && cleanPairs > 0)
                results.add(falseAlarm(e, perRule, flagged, cleanPairs));
        }
        return new Outcome(results, notRun, stopped);
    }

    // ── extractors ───────────────────────────────────────────────────────────────────────────

    private static ScenarioResult extractorCase(
            ExtractorDef x, JsonNode c, LLMClientFactory models, boolean mock, LongBinaryOperator addSpend) {
        String id = c.path("id").asText();
        String text = c.path("input").asText();
        if (mock)
            return row(x.getName(), "extractor", id, c, Status.UNJUDGED, List.of(unjudged("a mock run asks no model")), text, "", 0, 0, 0);
        try {
            LLMClient client = models.createClient(x.getModel());
            var got = NodeCaseRunner.extract(x, x.getInstructions(), text, client);
            addSpend.applyAsLong(got.inputTokens() + got.outputTokens(), 1);
            List<ScenarioResult.Check> checks = new ArrayList<>();
            if (got.failure() != null)
                checks.add(new ScenarioResult.Check("extract", "reads the text", Status.FAIL, got.failure()));
            else {
                JsonNode fields = c.get("expected_fields");
                if (fields != null)
                    fields.fields().forEachRemaining(f -> checks.add(fieldCheck(f.getKey(), f.getValue(), got.fields())));
                JsonNode missing = c.get("expected_missing");
                if (missing != null)
                    for (JsonNode m : missing) {
                        boolean ok = got.missing().contains(m.asText());
                        checks.add(
                                new ScenarioResult.Check(
                                        "missing",
                                        m.asText() + " is missing",
                                        ok ? Status.PASS : Status.FAIL,
                                        ok ? "" : "the extractor gave " + got.fields().get(m.asText())));
                    }
            }
            Status status = checks.stream().anyMatch(k -> k.status() == Status.FAIL) ? Status.FAIL : Status.PASS;
            return row(x.getName(), "extractor", id, c, status, checks, text, String.valueOf(got.fields()), got.inputTokens(), got.outputTokens(), 1);
        } catch (RuntimeException e) {
            return error(x.getName(), "extractor", id, c, text, e);
        }
    }

    private static ScenarioResult.Check fieldCheck(String field, JsonNode expected, Map<String, Object> actual) {
        Object got = actual.get(field);
        boolean ok = got != null && same(expected, got);
        return new ScenarioResult.Check(
                "field",
                field + " = " + expected.asText(),
                ok ? Status.PASS : Status.FAIL,
                ok ? "" : "the extractor gave " + got);
    }

    private static boolean same(JsonNode expected, Object got) {
        if (expected.isNumber() && got instanceof Number n) return expected.decimalValue().compareTo(new java.math.BigDecimal(n.toString())) == 0;
        if (expected.isBoolean()) return String.valueOf(got).equalsIgnoreCase(expected.asText());
        return String.valueOf(got).strip().equals(expected.asText().strip());
    }

    // ── evaluators ───────────────────────────────────────────────────────────────────────────

    private static ScenarioResult evaluatorCase(
            LoomScript script,
            EvaluatorDef e,
            JsonNode c,
            PolicyCatalog catalog,
            LLMClientFactory models,
            boolean mock,
            LongBinaryOperator addSpend,
            Map<String, int[]> perRule) {
        String id = c.path("id").asText();
        String text = c.path("text").asText();
        if (mock)
            return row(e.getName(), "evaluator", id, c, Status.UNJUDGED, List.of(unjudged("a mock run asks no model")), text, "", 0, 0, 0);
        PolicyDef policy = script.getPolicies().stream().filter(p -> p.getName().equals(c.path("policy").asText())).findFirst().orElse(null);
        var resolved = policy == null ? java.util.Optional.<PolicyCatalog.Resolved>empty() : catalog.resolve(policy);
        if (resolved.isEmpty())
            return error(e.getName(), "evaluator", id, c, text, new IllegalStateException("the policy could not be read"));
        try {
            var judged = NodeCaseRunner.judge(e, List.of(resolved.get()), text, m -> models.createClient(m));
            addSpend.applyAsLong(judged.inputTokens() + judged.outputTokens(), e.getSamples());
            List<ScenarioResult.Check> checks = new ArrayList<>();
            c.path("expected").fields().forEachRemaining(rule -> {
                String expected = rule.getValue().asText();
                String actual = judged.answers().getOrDefault(rule.getKey(), "unsure");
                boolean ok = expected.equals(actual);
                checks.add(new ScenarioResult.Check("rule", rule.getKey() + " is " + expected, ok ? Status.PASS : Status.FAIL, ok ? "" : "the evaluator said " + actual));
                if ("clean".equals(expected)) {
                    int[] counts = perRule.computeIfAbsent(rule.getKey(), k -> new int[2]);
                    counts[0]++;
                    if (!"clean".equals(actual)) counts[1]++;
                }
            });
            Status status = checks.stream().anyMatch(k -> k.status() == Status.FAIL) ? Status.FAIL : Status.PASS;
            return row(e.getName(), "evaluator", id, c, status, checks, text, String.valueOf(judged.answers()), judged.inputTokens(), judged.outputTokens(), e.getSamples());
        } catch (RuntimeException ex) {
            return error(e.getName(), "evaluator", id, c, text, ex);
        }
    }

    /** The rate is over clean and near-miss rule answers; above the limit the run fails. */
    private static ScenarioResult falseAlarm(EvaluatorDef e, Map<String, int[]> perRule, int flagged, int total) {
        double rate = (double) flagged / total;
        double limit = e.getFalseAlarmLimit();
        StringBuilder detail = new StringBuilder();
        perRule.forEach((rule, k) -> detail.append(rule).append(' ').append(k[1]).append(" of ").append(k[0]).append("; "));
        String interval = percent(Wilson.lower(flagged, total)) + " to " + percent(Wilson.upper(flagged, total));
        boolean over = rate > limit;
        String text = "false-alarm rate " + percent(rate) + " (" + flagged + " of " + total + " clean and near-miss answers flagged, 95% interval " + interval + "), limit " + percent(limit);
        return new ScenarioResult(
                e.getName(),
                "evaluator",
                e.getName().toLowerCase() + ".yaml",
                "false-alarm-rate",
                "false-alarm rate",
                over ? Status.FAIL : Status.PASS,
                List.of(new ScenarioResult.Check("false-alarm", text, over ? Status.FAIL : Status.PASS, detail.toString().strip())),
                null,
                null);
    }

    // ── rows ─────────────────────────────────────────────────────────────────────────────────

    private static ScenarioResult.Check unjudged(String why) {
        return new ScenarioResult.Check("node case", "checked against the expectation", Status.UNJUDGED, why);
    }

    private static ScenarioResult row(
            String node, String kind, String id, JsonNode c, Status status, List<ScenarioResult.Check> checks,
            String input, String output, long in, long out, long calls) {
        return new ScenarioResult(
                node, kind, node.toLowerCase() + ".yaml", id, c.path("name").asText(null), status, checks, null, null,
                input, output, List.of(), List.of(), List.of(new AgentFacts(node, calls, in, out, null, 0, 0)));
    }

    private static ScenarioResult error(String node, String kind, String id, JsonNode c, String input, RuntimeException e) {
        return new ScenarioResult(
                node, kind, node.toLowerCase() + ".yaml", id, c.path("name").asText(null), Status.FAIL,
                List.of(), null, e.getMessage(), input, null, List.of(), List.of(), List.of());
    }

    private static String percent(double share) {
        double tenths = Math.round(share * 1000) / 10.0;
        return (tenths == Math.floor(tenths) ? String.valueOf((long) tenths) : String.valueOf(tenths)) + "%";
    }
}
