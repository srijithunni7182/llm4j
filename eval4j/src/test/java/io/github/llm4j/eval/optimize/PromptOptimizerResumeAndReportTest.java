package io.github.llm4j.eval.optimize;

import static io.github.llm4j.eval.optimize.SimulationSupport.PARAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.eval.criteria.Criteria;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.report.EvalRecorder;
import io.github.llm4j.eval.support.StubJudge;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("eval4j-recorder")
class PromptOptimizerResumeAndReportTest {

    /** Simulates a killed process: an Error is not caught by the optimizer's listener guard. */
    private static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("simulated kill -9", null, false, false);
        }
    }

    private static PromptOptimizer.Builder slow() {
        return SimulationSupport.optimizer(SimulationSupport.slowRewriter())
                .targetValidationMean(1.0);
    }

    // S14: kill and resume -------------------------------------------------------------------

    @Test
    void s14_aKilledRunResumesToTheSameResultWithoutRepeatingWork(
            @TempDir Path fresh, @TempDir Path crashy) {
        SimulationSupport.CountingSystem reference =
                new SimulationSupport.CountingSystem(SimulationSupport.system());
        OptimizationResult uninterrupted =
                slow().system(reference).checkpointDir(fresh).build().run();

        SimulationSupport.CountingSystem first =
                new SimulationSupport.CountingSystem(SimulationSupport.system());
        assertThatThrownBy(
                        () ->
                                slow().system(first)
                                        .checkpointDir(crashy)
                                        .listener(
                                                new OptimizerListener() {
                                                    @Override
                                                    public void onRound(Round round) {
                                                        if (round.index() == 3) {
                                                            throw new SimulatedCrash();
                                                        }
                                                    }
                                                })
                                        .build()
                                        .run())
                .isInstanceOf(SimulatedCrash.class);

        SimulationSupport.CountingSystem second =
                new SimulationSupport.CountingSystem(SimulationSupport.system());
        OptimizationResult resumed = slow().system(second).checkpointDir(crashy).build().run();

        assertThat(resumed.trace()).isEqualTo(uninterrupted.trace());
        assertThat(resumed.best()).isEqualTo(uninterrupted.best());
        assertThat(resumed.stopReason()).isEqualTo(uninterrupted.stopReason());
        assertThat(resumed.bestScores().testMean())
                .isEqualTo(uninterrupted.bestScores().testMean());
        assertThat(first.count() + second.count())
                .as("no rollout repeated")
                .isEqualTo(reference.count());
        assertThat(resumed.cost().rollouts()).isEqualTo(uninterrupted.cost().rollouts());
    }

    @Test
    void aCheckpointFromADifferentConfigurationIsRefused(@TempDir Path dir) {
        slow().checkpointDir(dir)
                .budget(OptimizerBudget.builder().maxRounds(1).build())
                .build()
                .run();

        PromptOptimizer.Builder changed =
                slow().checkpointDir(dir).seed(Candidate.of(PARAM, "a different seed"));

        assertThatThrownBy(() -> changed.build().run())
                .isInstanceOf(OptimizerConfigurationException.class)
                .hasMessageContaining("belongs to a different run")
                .hasMessageContaining(Checkpointer.FILE_NAME);
    }

    @Test
    void runningAgainOverACompletedCheckpointReproducesTheResult(@TempDir Path dir) {
        OptimizationResult first =
                slow().checkpointDir(dir)
                        .budget(OptimizerBudget.builder().maxRounds(3).build())
                        .build()
                        .run();
        OptimizationResult again =
                slow().checkpointDir(dir)
                        .budget(OptimizerBudget.builder().maxRounds(3).build())
                        .build()
                        .run();

        assertThat(again.trace()).isEqualTo(first.trace());
        assertThat(again.best()).isEqualTo(first.best());
        assertThat(again.stopReason()).isEqualTo(StopReason.MAX_ROUNDS);
    }

    // reports and patch ------------------------------------------------------------------------

    @Test
    void writeReportProducesATraceAndAMarkdownReportAndNothingElse(@TempDir Path dir)
            throws Exception {
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter()).build().run();

        result.writeReport(dir);

        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder(
                            OptimizationReport.TRACE_FILE, OptimizationReport.REPORT_FILE);
        }
        JsonNode trace =
                new ObjectMapper()
                        .readTree(Files.readAllBytes(dir.resolve(OptimizationReport.TRACE_FILE)));
        assertThat(trace.get("stopReason").asText()).isEqualTo("TARGET_REACHED");
        assertThat(trace.get("generalized").asBoolean()).isTrue();
        assertThat(trace.get("rounds")).hasSize(result.trace().size());
        assertThat(trace.get("best").get("parameters").get(PARAM).asText()).contains("[L:units]");
        assertThat(trace.get("cost").get("rollouts").asLong()).isEqualTo(result.cost().rollouts());
        String markdown = Files.readString(dir.resolve(OptimizationReport.REPORT_FILE));
        assertThat(markdown)
                .contains(
                        "# Optimizer report",
                        "**Generalized:** yes",
                        "## Scores",
                        "## Cost",
                        "## Change",
                        "```diff",
                        "## Rounds",
                        "TARGET_REACHED");
    }

    @Test
    void theReportSaysSoWhenTheSeedWasNotImproved(@TempDir Path dir) throws Exception {
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.rewriter((c, f) -> c))
                        .patience(2)
                        .build()
                        .run();
        result.writeReport(dir);
        String markdown = Files.readString(dir.resolve(OptimizationReport.REPORT_FILE));
        assertThat(markdown)
                .contains(
                        "**Generalized:** **NO**",
                        "no candidate improved on the seed",
                        "No change: the seed remained");
        assertThat(result.toPatch().isEmpty()).isTrue();
    }

    @Test
    void freeTextCannotBreakTheMarkdownTable() {
        Round round =
                new Round(
                        1,
                        "c0",
                        "p",
                        RoundAction.REJECTED_CONSTRAINT,
                        null,
                        List.of(),
                        0.0,
                        null,
                        null,
                        false,
                        1,
                        1,
                        "line one | pipe\nline two");
        OptimizationResult base =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter()).build().run();
        OptimizationResult withNote =
                new OptimizationResult(
                        base.seed(),
                        base.best(),
                        base.stopReason(),
                        base.seedScores(),
                        base.bestScores(),
                        base.bestSelectionValidationMean(),
                        base.verdict(),
                        base.seedVsBest(),
                        base.cost(),
                        List.of(round),
                        List.of("warn | ing"));

        String markdown = OptimizationReport.markdown(withNote);

        assertThat(markdown).contains("line one \\| pipe line two").contains("warn \\| ing");
    }

    // safety ----------------------------------------------------------------------------------

    @Test
    void secretsAreRedactedFromEveryPersistedArtifactAndFromTheRewriter(
            @TempDir Path checkpoint, @TempDir Path report) throws Exception {
        String secret = "sk-ant-TOPSECRET-123";
        StubJudge rewriter = SimulationSupport.lessonRewriter();
        SystemUnderTest leaky = (c, s) -> SimulationSupport.system().run(c, s) + ";token=" + secret;

        OptimizationResult result =
                SimulationSupport.optimizer(rewriter)
                        .system(leaky)
                        .checkpointDir(checkpoint)
                        .redactor(text -> text.replace(secret, "[REDACTED]"))
                        .build()
                        .run();
        result.writeReport(report);

        assertThat(rewriter.requests())
                .allSatisfy(r -> assertThat(StubJudge.userMessage(r)).doesNotContain(secret));
        for (Path dir : List.of(checkpoint, report)) {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    assertThat(Files.readString(file))
                            .as(file.getFileName().toString())
                            .doesNotContain(secret);
                }
            }
        }
    }

    @Test
    void aClientsToStringNeverReachesTracesOrReports(@TempDir Path report) throws Exception {
        StubJudge inner = SimulationSupport.lessonRewriter();
        io.github.llm4j.LLMClient leakyToString =
                new io.github.llm4j.LLMClient() {
                    @Override
                    public io.github.llm4j.model.LLMResponse chat(
                            io.github.llm4j.model.LLMRequest request) {
                        return inner.chat(request);
                    }

                    @Override
                    public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(
                            io.github.llm4j.model.LLMRequest request) {
                        return inner.chatStream(request);
                    }

                    @Override
                    public String toString() {
                        return "Client{apiKey=sk-ant-FROM-TOSTRING}";
                    }
                };
        OptimizationResult result =
                SimulationSupport.optimizer(inner)
                        .rewriter(leakyToString)
                        .judge(leakyToString)
                        .build()
                        .run();
        result.writeReport(report);
        try (var files = Files.list(report)) {
            for (Path file : files.toList()) {
                assertThat(Files.readString(file)).doesNotContain("FROM-TOSTRING");
            }
        }
        assertThat(result.warnings()).noneMatch(w -> w.contains("FROM-TOSTRING"));
    }

    // observers and side channels --------------------------------------------------------------

    @Test
    void theListenerSeesEveryRoundAndStopAndAFailingListenerDoesNotAbortTheRun() {
        List<Round> seen = new ArrayList<>();
        List<StopReason> stops = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .listener(
                                new OptimizerListener() {
                                    @Override
                                    public void onRound(Round round) {
                                        seen.add(round);
                                        failures.incrementAndGet();
                                        throw new IllegalStateException("listener bug");
                                    }

                                    @Override
                                    public void onStop(StopReason reason) {
                                        stops.add(reason);
                                    }
                                })
                        .build()
                        .run();

        assertThat(seen).isEqualTo(result.trace());
        assertThat(stops).containsExactly(StopReason.TARGET_REACHED);
        assertThat(result.warnings()).anyMatch(w -> w.contains("listener failed on round 1"));
        assertThat(failures.get()).isEqualTo(result.trace().size());
    }

    @Test
    void theConsoleListenerPrintsRoundsWarningsAndTheStopReason() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(captured));
            StubJudge shared = SimulationSupport.lessonRewriter();
            SimulationSupport.optimizer(shared)
                    .rewriter(shared)
                    .judge(shared)
                    .listener(OptimizerListener.console())
                    .build()
                    .run();
        } finally {
            System.setOut(original);
        }
        assertThat(captured.toString())
                .contains(
                        "[optimizer] round 1: ACCEPTED",
                        "WARNING: the rewriter and the judge",
                        "[optimizer] stopped: TARGET_REACHED");
    }

    @Test
    void eachRoundRecordsTheBestValidationMeanForReportsAndBaselines() {
        EvalRecorder.reset();
        EvalRecorder.activate();
        try {
            OptimizationResult result =
                    SimulationSupport.optimizer(SimulationSupport.lessonRewriter()).build().run();
            var records =
                    EvalRecorder.records().stream()
                            .filter(r -> r.metric().equals("Optimizer: best validation"))
                            .toList();
            assertThat(records).hasSize(result.trace().size());
            assertThat(records.get(records.size() - 1).score()).isGreaterThanOrEqualTo(0.95);
        } finally {
            EvalRecorder.reset();
        }
    }

    @Test
    void llmCallsMadeThroughACounterAreTrackedInTheCostAndBudget() {
        LlmCallCounter counter = LlmCallCounter.wrap(StubJudge.always("ok"));
        SystemUnderTest callsAnLlm =
                (c, s) -> {
                    counter.chat(
                            io.github.llm4j.model.LLMRequest.builder()
                                    .addUserMessage("hi")
                                    .build());
                    return SimulationSupport.system().run(c, s);
                };
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.slowRewriter())
                        .system(callsAnLlm)
                        .trackCalls(counter)
                        .budget(OptimizerBudget.builder().maxLlmCalls(60).build())
                        .targetValidationMean(1.0)
                        .build()
                        .run();

        assertThat(result.stopReason()).isEqualTo(StopReason.MAX_LLM_CALLS);
        assertThat(result.cost().trackedLlmCalls()).isEqualTo(counter.count());
        assertThat(result.cost().trackedLlmCalls()).isPositive();
    }

    // verdict corner cases ------------------------------------------------------------------

    @Test
    void withoutATestSplitTheResultIsNeverGeneralized() {
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .split(Split.ratios(0.6, 0.4, 0).allowNoTest())
                        .build()
                        .run();

        assertThat(result.best()).isNotEqualTo(result.seed());
        assertThat(result.bestScores().testMean()).isNull();
        assertThat(result.generalized()).isFalse();
        assertThat(result.verdict().reasons()).anyMatch(r -> r.contains("no test split"));
        assertThat(result.seedVsBest()).isNull();
    }

    @Test
    void guardrailViolationsOnTheTestSplitPreventGeneralization() {
        var all = SimulationSupport.scenarios(8);
        var train = all.stream().filter(s -> number(s) <= 4).toList();
        var validation = all.stream().filter(s -> number(s) > 4 && number(s) <= 6).toList();
        List<EvalScenario> hidden = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            hidden.add(
                    new EvalScenario("units-h" + i, "hidden " + i, null, null, null, null, null));
        }
        var noHiddenNames =
                Criteria.guardrail(
                        "not-hidden",
                        out -> {
                            if (String.valueOf(out).contains("HIDDEN")) {
                                throw new AssertionError("hidden scenario output");
                            }
                        });
        SystemUnderTest system =
                (c, s) ->
                        SimulationSupport.system().run(c, s)
                                + (s.name().contains("-h") ? "HIDDEN" : "");
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .system(system)
                        .criteria(
                                List.of(SimulationSupport.partialCreditCriterion(), noHiddenNames))
                        .split(Split.explicit(all.subList(0, 12), all.subList(12, 18), hidden))
                        .scenarios(List.of())
                        .build()
                        .run();

        assertThat(result.generalized()).isFalse();
        assertThat(result.verdict().reasons()).anyMatch(r -> r.contains("violate a guardrail"));
    }

    @Test
    void theSeedBeatingTheBestOnMoreTestScenariosPreventsGeneralization() {
        // best wins big on one test scenario and loses slightly on four: the mean gain looks fine
        // (+0.1) but the paired comparison shows the seed is better on most scenarios
        SystemUnderTest system =
                (c, s) -> "name=" + s.name() + ";has=" + c.get(PARAM).contains("[L:units]");
        var criterion =
                Criteria.judged(
                        "skewed",
                        out -> {
                            String text = String.valueOf(out);
                            String name = text.substring(5, text.indexOf(';'));
                            boolean has = text.endsWith("true");
                            double score =
                                    !has
                                            ? 0.2
                                            : name.startsWith("units-")
                                                    ? 1.0
                                                    : name.equals("t-1") ? 1.0 : 0.15;
                            return new io.github.llm4j.eval.judge.JudgeVerdict(
                                    score,
                                    has ? "ok" : "Missing lesson for category units: not covered");
                        },
                        0.99);
        var train = new ArrayList<EvalScenario>();
        var validation = new ArrayList<EvalScenario>();
        var test = new ArrayList<EvalScenario>();
        for (int i = 1; i <= 8; i++) {
            train.add(new EvalScenario("units-" + i, "q" + i, null, null, null, null, null));
            validation.add(new EvalScenario("units-v" + i, "v" + i, null, null, null, null, null));
        }
        for (int i = 1; i <= 5; i++) {
            test.add(new EvalScenario("t-" + i, "t" + i, null, null, null, null, null));
        }
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .system(system)
                        .criteria(List.of(criterion))
                        .split(Split.explicit(train, validation, test))
                        .scenarios(List.of())
                        .build()
                        .run();

        assertThat(result.bestScores().testMean() - result.seedScores().testMean())
                .isGreaterThan(0.02);
        assertThat(result.seedVsBest().seedWins()).isEqualTo(4);
        assertThat(result.seedVsBest().bestWins()).isEqualTo(1);
        assertThat(result.generalized()).isFalse();
        assertThat(result.verdict().reasons())
                .anyMatch(
                        r ->
                                r.contains(
                                        "the seed beats the best candidate on more test scenarios"));
    }

    @Test
    void comparisonCountsWinsLossesAndTiesAndGivesAnIntervalOnlyWhenDecisive() {
        var seed = List.of(card(0.2), card(0.5), card(0.5));
        var best = List.of(card(0.9), card(0.5), card(0.1));
        Comparison c = Comparison.of(seed, best);
        assertThat(c.bestWins()).isEqualTo(1);
        assertThat(c.seedWins()).isEqualTo(1);
        assertThat(c.ties()).isEqualTo(1);
        assertThat(c.interval()).isNotNull();
        assertThat(Comparison.of(List.of(card(0.5)), List.of(card(0.5))).interval()).isNull();
    }

    private static int number(EvalScenario scenario) {
        return Integer.parseInt(scenario.name().substring(scenario.name().indexOf('-') + 1));
    }

    private static io.github.llm4j.eval.criteria.Scorecard card(double score) {
        return new io.github.llm4j.eval.criteria.Scorecard(
                "s", score, false, List.of(), "", "", false);
    }
}
