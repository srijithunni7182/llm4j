package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.eval.EvalReport;
import io.github.llm4j.loom.eval.EvalSections;
import io.github.llm4j.loom.eval.ScenarioResult;
import io.github.llm4j.loom.eval.Status;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R10 and R11 of docs/specs/28: node cases run for real, a false-alarm rate, rows in the report. */
class NodeCasesEvalTest {

    @TempDir Path dir;

    private final AtomicInteger calls = new AtomicInteger();

    private static String script(String limit) {
        return "evaluator Compliance { model: \"judge\"" + (limit == null ? "" : "  false_alarm_limit: " + limit) + " }\n"
                + "policy HouseRules: \"house-rules\" checked by Compliance\n"
                + "extractor Invoice { model: \"reader\"  fields { vendor: string  total: number }  optional: [total] }\n"
                + "workflow Main(q) { extract Invoice from q -> i\n check q against HouseRules using Compliance -> v on_violation { fail }\n return q }\n";
    }

    private static final String COMPLIANCE_CASES =
            """
            - id: c1
              policy: HouseRules
              text: "We will refund you."
              expected: { no_promise: violated }
            - id: c2
              policy: HouseRules
              near_miss: true
              text: "I cannot promise a refund."
              expected: { no_promise: clean }
            - id: c3
              policy: HouseRules
              text: "Happy to help."
              expected: { no_promise: clean }
            """;

    @BeforeEach
    void project() throws Exception {
        Files.createDirectories(dir.resolve("policies"));
        Files.writeString(dir.resolve("policies/house-rules.md"), "## no_promise\nThe reply promises a refund.\n");
        Path g = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(g.resolve("compliance.yaml"), COMPLIANCE_CASES);
        Files.writeString(
                g.resolve("invoice.yaml"),
                "- id: i1\n  input: \"Acme 240\"\n  expected_fields: { vendor: Acme, total: 240 }\n"
                        + "- id: i2\n  input: \"Acme thanks\"\n  expected_fields: { vendor: Acme }\n  expected_missing: [total]\n");
    }

    /** The judge flags any text that mentions a refund (so the near miss is a false alarm); the reader reads Acme. */
    private io.github.llm4j.loom.execution.LLMClientFactory models(boolean flagNearMiss) {
        io.github.llm4j.LLMClient client =
                new io.github.llm4j.LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest r) {
                        calls.incrementAndGet();
                        String all = r.getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
                        String reply;
                        if (all.contains("You are a policy evaluator.")) {
                            boolean flag = all.contains("We will refund you") || (flagNearMiss && all.contains("cannot promise a refund"));
                            reply = "{\"no_promise\":{\"violated\":\"" + (flag ? "yes" : "no") + "\",\"reason\":\"r\"}}";
                        } else {
                            boolean total = all.contains("Acme 240");
                            reply = total ? "{\"vendor\":\"Acme\",\"total\":240}" : "{\"vendor\":\"Acme\",\"total\":null}";
                        }
                        return LLMResponse.builder().content(reply).model("test").build();
                    }

                    @Override
                    public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest request) {
                        return java.util.stream.Stream.empty();
                    }
                };
        return model -> client;
    }

    private NodeCasesEval.Outcome run(String limit, boolean flagNearMiss, boolean mock, Set<String> only, boolean exhausted) throws Exception {
        Path file = Files.writeString(dir.resolve("main.loom"), script(limit));
        LoomScript parsed = new LoomParser(new Lexer(script(limit)).tokenize()).parseScript();
        return NodeCasesEval.run(
                parsed, file, dir.resolve("eval/golden"), models(flagNearMiss), mock, only, () -> exhausted, (t, c) -> t, null);
    }

    private static ScenarioResult row(NodeCasesEval.Outcome o, String node, String id) {
        return o.results().stream().filter(r -> r.target().equals(node) && r.id().equals(id)).findFirst().orElseThrow();
    }

    // ── extractors ───────────────────────────────────────────────────────────────────────────

    @Test
    void anExtractorCasePassesWhenTheFieldsAndTheMissingOnesMatch() throws Exception {
        var o = run(null, false, false, null, false);
        assertThat(row(o, "Invoice", "i1").status()).isEqualTo(Status.PASS);
        assertThat(row(o, "Invoice", "i2").status()).isEqualTo(Status.PASS);
    }

    @Test
    void anExtractorCaseFailsAndSaysWhatItGaveWhenAFieldIsWrong() throws Exception {
        Files.writeString(
                dir.resolve("eval/golden/invoice.yaml"),
                "- id: i1\n  input: \"Acme 240\"\n  expected_fields: { vendor: Acme, total: 999 }\n");
        var r = row(run(null, false, false, null, false), "Invoice", "i1");
        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.checks()).anySatisfy(c -> assertThat(c.detail()).contains("240"));
    }

    // ── evaluators and the false-alarm rate ──────────────────────────────────────────────────

    @Test
    void anEvaluatorCasePassesWhenEveryRuleAnswerMatches() throws Exception {
        var o = run(null, false, false, null, false);
        assertThat(row(o, "Compliance", "c1").status()).isEqualTo(Status.PASS);
        assertThat(row(o, "Compliance", "c3").status()).isEqualTo(Status.PASS);
    }

    @Test
    void aTimidEvaluatorFailsTheRunWithItsRateAndInterval() throws Exception {
        var o = run(null, true, false, null, false);
        assertThat(row(o, "Compliance", "c2").status()).isEqualTo(Status.FAIL);
        ScenarioResult rate = row(o, "Compliance", "false-alarm-rate");
        assertThat(rate.status()).isEqualTo(Status.FAIL);
        assertThat(rate.checks().get(0).text()).contains("50%").contains("1 of 2").contains("95% interval").contains("limit 10%");
        assertThat(rate.checks().get(0).detail()).contains("no_promise 1 of 2");
    }

    @Test
    void theLimitIsPerEvaluatorAndAtTheLimitIsStillFine() throws Exception {
        var o = run("50%", true, false, null, false);
        assertThat(row(o, "Compliance", "false-alarm-rate").status()).isEqualTo(Status.PASS);
        var strict = run("49%", true, false, null, false);
        assertThat(row(strict, "Compliance", "false-alarm-rate").status()).isEqualTo(Status.FAIL);
    }

    @Test
    void aSteadyEvaluatorHasAZeroRateAndPasses() throws Exception {
        var rate = row(run(null, false, false, null, false), "Compliance", "false-alarm-rate");
        assertThat(rate.status()).isEqualTo(Status.PASS);
        assertThat(rate.checks().get(0).text()).contains("0%");
    }

    // ── mock, caps, filters ──────────────────────────────────────────────────────────────────

    @Test
    void aMockRunAsksNoModelLeavesTheCasesUnjudgedAndClaimsNoRate() throws Exception {
        var o = run(null, true, true, null, false);
        assertThat(calls.get()).isZero();
        assertThat(o.results()).isNotEmpty().allSatisfy(r -> assertThat(r.status()).isEqualTo(Status.UNJUDGED));
        assertThat(o.results()).noneMatch(r -> r.id().equals("false-alarm-rate"));
    }

    @Test
    void aLimitAlreadyReachedMeansNothingIsAskedAndTheCasesAreCountedNotRun() throws Exception {
        var o = run(null, false, false, null, true);
        assertThat(calls.get()).isZero();
        assertThat(o.notRun()).isEqualTo(5);
        assertThat(o.stopped()).isNotNull();
    }

    @Test
    void onlyTheNamedNodesRun() throws Exception {
        var o = run(null, false, false, Set.of("Invoice"), false);
        assertThat(o.results()).allSatisfy(r -> assertThat(r.target()).isEqualTo("Invoice"));
    }

    // ── the report ───────────────────────────────────────────────────────────────────────────

    @Test
    void theReportHasRowsForNodesUnderByAgentAndNode() throws Exception {
        var o = run(null, true, false, null, false);
        EvalReport.Run run = new EvalReport.Run("main.loom", false, new ArrayList<>(o.results()), 0, null, Map.of(), null, Map.of(), "weave eval main.loom");
        String text = EvalSections.terminal(run);
        assertThat(text).contains("By agent and node").contains("Compliance").contains("Invoice");
        assertThat(run.exitCode()).isNotZero();
    }

    @Test
    void theLimitParsesAsAShareOrAPercentAndRefusesNonsense() {
        LoomScript s = new LoomParser(new Lexer("evaluator E { model: \"m\"  false_alarm_limit: 5% }").tokenize()).parseScript();
        assertThat(s.getEvaluators().get(0).getFalseAlarmLimit()).isEqualTo(0.05);
        LoomScript t = new LoomParser(new Lexer("evaluator E { model: \"m\" }").tokenize()).parseScript();
        assertThat(t.getEvaluators().get(0).getFalseAlarmLimit()).isEqualTo(0.10);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new LoomParser(new Lexer("evaluator E { model: \"m\"  false_alarm_limit: 250% }").tokenize()).parseScript())
                .hasMessageContaining("false_alarm_limit");
    }
}
