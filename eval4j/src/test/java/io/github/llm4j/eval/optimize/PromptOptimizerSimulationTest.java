package io.github.llm4j.eval.optimize;

import static io.github.llm4j.eval.optimize.SimulationSupport.PARAM;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.criteria.Criteria;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.support.StubJudge;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Full optimizer runs against a simulated world with a known optimum (see SimulationSupport). */
class PromptOptimizerSimulationTest {

    private static final DataSplit SPLIT =
            Split.ratios(0.5, 0.3, 0.2).seed(1).apply(SimulationSupport.scenarios(15));

    // S1: convergence -------------------------------------------------------------------------

    @Test
    void s1_convergesToTheKnownOptimumAndGeneralizes() {
        StubJudge rewriter = SimulationSupport.lessonRewriter();
        OptimizationResult result = SimulationSupport.optimizer(rewriter).build().run();

        assertThat(result.stopReason()).isEqualTo(StopReason.TARGET_REACHED);
        assertThat(result.best().get(PARAM))
                .contains("[L:units]", "[L:dates]", "[L:currency]", "[L:names]");
        assertThat(result.bestSelectionValidationMean()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.bestScores().validationMean()).isEqualTo(1.0);
        assertThat(result.seedScores().testMean())
                .isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.bestScores().testMean()).isEqualTo(1.0);
        assertThat(result.generalized()).isTrue();
        assertThat(result.verdict().reasons().get(0)).contains("test mean 1.000 vs seed 0.200");
        assertThat(result.seedVsBest().bestWins()).isEqualTo(SPLIT.test().size());
        assertThat(result.seedVsBest().seedWins()).isZero();
        assertThat(result.cost().rewriterCalls()).isEqualTo(rewriter.callCount());
        assertThat(result.trace()).isNotEmpty();
        assertThat(result.trace()).extracting(Round::action).contains(RoundAction.ACCEPTED);
        assertThat(result.toPatch().changedParameters()).containsExactly(PARAM);
    }

    // S2: hard rollout budget ----------------------------------------------------------------

    @Test
    void s2_neverExceedsTheRolloutBudget_andStillRunsTheFinalPhase() {
        for (long cap : new long[] {68, 75, 100, 137, 250}) {
            SimulationSupport.CountingSystem system =
                    new SimulationSupport.CountingSystem(SimulationSupport.system());
            OptimizationResult result =
                    SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                            .system(system)
                            .budget(OptimizerBudget.builder().maxRollouts(cap).build())
                            .build()
                            .run();

            assertThat(system.count()).as("cap " + cap).isLessThanOrEqualTo(cap);
            assertThat(result.cost().rollouts()).isLessThanOrEqualTo(cap);
            assertThat(result.stopReason())
                    .as("cap " + cap)
                    .isIn(StopReason.MAX_ROLLOUTS, StopReason.TARGET_REACHED);
            assertThat(result.bestScores().test())
                    .as("final phase ran under cap " + cap)
                    .isNotEmpty();
        }
    }

    // S3: every stop reason -------------------------------------------------------------------

    @Test
    void s3_maxRoundsMaxLlmCallsAndMaxDurationStopTheLoop() {
        assertThat(
                        SimulationSupport.optimizer(SimulationSupport.slowRewriter())
                                .budget(OptimizerBudget.builder().maxRounds(2).build())
                                .targetValidationMean(1.0)
                                .build()
                                .run()
                                .stopReason())
                .isEqualTo(StopReason.MAX_ROUNDS);

        StubJudge rewriter = SimulationSupport.slowRewriter();
        OptimizationResult calls =
                SimulationSupport.optimizer(rewriter)
                        .budget(OptimizerBudget.builder().maxLlmCalls(2).build())
                        .targetValidationMean(1.0)
                        .build()
                        .run();
        assertThat(calls.stopReason()).isEqualTo(StopReason.MAX_LLM_CALLS);
        assertThat(rewriter.callCount()).isEqualTo(2);

        BudgetTest.MutableClock clock = new BudgetTest.MutableClock();
        OptimizationResult timed =
                SimulationSupport.optimizer(SimulationSupport.slowRewriter())
                        .system(
                                (c, s) -> {
                                    clock.advance(Duration.ofSeconds(1));
                                    return SimulationSupport.system().run(c, s);
                                })
                        .clock(clock)
                        .budget(
                                OptimizerBudget.builder()
                                        .maxDuration(Duration.ofSeconds(40))
                                        .build())
                        .targetValidationMean(1.0)
                        .build()
                        .run();
        assertThat(timed.stopReason()).isEqualTo(StopReason.MAX_DURATION);
    }

    @Test
    void s3_interruptCancelsWithoutTouchingTheFinalPhase() {
        AtomicInteger calls = new AtomicInteger();
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.slowRewriter())
                        .system(
                                (c, s) -> {
                                    if (calls.incrementAndGet() == 30) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return SimulationSupport.system().run(c, s);
                                })
                        .targetValidationMean(1.0)
                        .build()
                        .run();
        Thread.interrupted(); // clear the flag we set

        assertThat(result.stopReason()).isEqualTo(StopReason.CANCELLED);
        assertThat(result.generalized()).isFalse();
        assertThat(result.verdict().reasons().get(0)).contains("cancelled");
        assertThat(result.bestScores().test()).isEmpty();
    }

    @Test
    void s3_anUnscorableSeedFailsTheRunImmediately() {
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .system(
                                (c, s) -> {
                                    throw new IllegalStateException("agent is broken");
                                })
                        .build()
                        .run();

        assertThat(result.stopReason()).isEqualTo(StopReason.FAILED);
        assertThat(result.best()).isEqualTo(result.seed());
        assertThat(result.warnings()).anyMatch(w -> w.contains("seed could not be scored"));
        assertThat(result.generalized()).isFalse();
        assertThat(result.trace()).isEmpty();
    }

    @Test
    void s3_aSeedThatAlreadyMeetsTheTargetStopsAtOnceAndIsNotAnImprovement() {
        StubJudge rewriter = SimulationSupport.lessonRewriter();
        OptimizationResult result =
                SimulationSupport.optimizer(rewriter)
                        .seed(Candidate.of(PARAM, "[L:units] [L:dates] [L:currency] [L:names]"))
                        .build()
                        .run();

        assertThat(result.stopReason()).isEqualTo(StopReason.TARGET_REACHED);
        assertThat(rewriter.callCount()).isZero();
        assertThat(result.generalized()).isFalse();
        assertThat(result.verdict().reasons()).containsExactly("no candidate improved on the seed");
    }

    // S4: a rewriter that cannot help ---------------------------------------------------------

    @Test
    void s4_aNoOpRewriterIsRejectedAsDuplicatesAndTheRunEndsWithNoProgress() {
        SimulationSupport.CountingSystem system =
                new SimulationSupport.CountingSystem(SimulationSupport.system());
        StubJudge noOp = SimulationSupport.rewriter((current, failures) -> current);
        OptimizationResult result =
                SimulationSupport.optimizer(noOp).system(system).patience(3).build().run();

        assertThat(result.stopReason()).isEqualTo(StopReason.NO_PROGRESS);
        assertThat(result.trace())
                .extracting(Round::action)
                .containsOnly(RoundAction.REJECTED_DUPLICATE);
        assertThat(result.trace()).hasSize(3);
        assertThat(result.best()).isEqualTo(result.seed());
        assertThat(result.generalized()).isFalse();
        // rejected duplicates cost only the parent batch: seed validation + 3 batches + test run
        assertThat(system.count())
                .isEqualTo(SPLIT.validation().size() + 3L * 4 + SPLIT.test().size());
    }

    @Test
    void s4_aRewriterReturningGarbageIsRetriedOnceThenRecordedAsFailed() {
        StubJudge garbage = new StubJudge(r -> "I would rather chat than return JSON");
        OptimizationResult result = SimulationSupport.optimizer(garbage).patience(2).build().run();

        assertThat(result.trace())
                .extracting(Round::action)
                .containsOnly(RoundAction.REWRITE_FAILED);
        assertThat(garbage.callCount()).isEqualTo(4); // two rounds x (attempt + repair)
        assertThat(result.trace().get(0).note()).contains("did not return a usable");
        assertThat(result.stopReason()).isEqualTo(StopReason.NO_PROGRESS);
    }

    // S5: the gate ---------------------------------------------------------------------------

    @Test
    void s5_aChildThatIsWorseOnTheBatchIsNeverScoredOnValidation() {
        SimulationSupport.CountingSystem system =
                new SimulationSupport.CountingSystem(SimulationSupport.system());
        StubJudge dropsLessons =
                SimulationSupport.rewriter((current, failures) -> "You are helpful");
        OptimizationResult result =
                SimulationSupport.optimizer(dropsLessons)
                        .system(system)
                        .seed(Candidate.of(PARAM, "[L:units] [L:dates] be helpful"))
                        .patience(2)
                        .build()
                        .run();

        assertThat(result.trace().get(0).action()).isEqualTo(RoundAction.GATE_FAILED);
        assertThat(result.trace().get(0).childBatchMean())
                .isLessThan(result.trace().get(0).parentBatchMean());
        // validation scenarios ran exactly once: the seed's own scoring
        assertThat(system.countFor(SPLIT.validation())).isEqualTo(SPLIT.validation().size());
    }

    // S7: overfitting -------------------------------------------------------------------------

    @Test
    void s7_memorizingTrainAnswersDoesNotWinSelection() {
        // score is 1.0 if the prompt has the category lesson OR a memo for that exact scenario
        var system = (SystemUnderTest) (c, s) -> "cat=" + s.name() + ";lessons=" + c.get(PARAM);
        var criterion =
                Criteria.judged(
                        "memo",
                        out -> {
                            String text = String.valueOf(out);
                            String name = text.substring(4, text.indexOf(';'));
                            boolean has = text.contains("[M:" + name + "]");
                            return new io.github.llm4j.eval.judge.JudgeVerdict(
                                    has ? 1.0 : 0.2, has ? "ok" : "Missing memo for " + name);
                        },
                        0.99);
        StubJudge memorizer =
                new StubJudge(
                        r -> {
                            String user = StubJudge.userMessage(r);
                            StringBuilder text =
                                    new StringBuilder(
                                            SimulationSupport.section(user, "CURRENT TEXT"));
                            java.util.regex.Matcher m =
                                    java.util.regex.Pattern.compile("(\\w+) question (\\d+)")
                                            .matcher(SimulationSupport.section(user, "FAILURES"));
                            while (m.find()) {
                                text.append(" [M:")
                                        .append(m.group(1))
                                        .append('-')
                                        .append(m.group(2))
                                        .append(']');
                            }
                            return SimulationSupport.jsonReply(text.toString());
                        });

        OptimizationResult result =
                SimulationSupport.optimizer(memorizer)
                        .system(system)
                        .criteria(List.of(criterion))
                        .budget(OptimizerBudget.builder().maxRounds(6).build())
                        .targetValidationMean(1.0)
                        .build()
                        .run();

        // memos raise the training batch score, but validation and test scenarios are different
        assertThat(result.trace()).extracting(Round::action).contains(RoundAction.ACCEPTED);
        assertThat(result.bestScores().validationMean())
                .isEqualTo(result.seedScores().validationMean());
        assertThat(result.best()).isEqualTo(result.seed());
        assertThat(result.generalized()).isFalse();
    }

    @Test
    void s7_validationGainsThatDoNotTransferToTheSealedTestSplitAreNotGeneralized() {
        List<EvalScenario> train = SimulationSupport.scenarios(6);
        List<EvalScenario> validation =
                SimulationSupport.scenarios(6).stream()
                        .map(
                                s ->
                                        new EvalScenario(
                                                "val-" + s.name(),
                                                s.input() + " (val)",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                        .toList();
        List<EvalScenario> hidden = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            hidden.add(
                    new EvalScenario(
                            "hidden-" + i, "hidden question " + i, null, null, null, null, null));
        }
        // validation scenarios reuse the real categories through their names; the hidden test
        // category
        // is never in train, so the rewriter can never learn its lesson
        SystemUnderTest system =
                (c, s) -> {
                    String name = s.name().startsWith("val-") ? s.name().substring(4) : s.name();
                    return SimulationSupport.system()
                            .run(
                                    c,
                                    new EvalScenario(
                                            name, s.input(), null, null, null, null, null));
                };
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .system(system)
                        .split(Split.explicit(train, validation, hidden))
                        .scenarios(List.of())
                        .criteria(List.of(SimulationSupport.partialCreditCriterion()))
                        .targetValidationMean(1.0)
                        .budget(OptimizerBudget.builder().maxRounds(20).build())
                        .build()
                        .run();

        assertThat(result.bestScores().validationMean())
                .isGreaterThan(result.seedScores().validationMean());
        assertThat(result.bestScores().testMean()).isEqualTo(result.seedScores().testMean());
        assertThat(result.generalized()).isFalse();
        assertThat(result.verdict().reasons()).anyMatch(r -> r.contains("test-split gain"));
        assertThat(result.verdict().reasons()).anyMatch(r -> r.contains("overfitting"));
    }

    // S8: reward hacking ----------------------------------------------------------------------

    /** A gullible judge: any long prompt earns 0.9, whatever it says. */
    private static SystemUnderTest verbosityDetector() {
        return (c, s) ->
                "cat="
                        + s.name().substring(0, s.name().indexOf('-'))
                        + ";lessons="
                        + c.get(PARAM)
                        + ";verbose="
                        + (c.get(PARAM).length() > 150);
    }

    private static io.github.llm4j.eval.criteria.Criterion gullibleJudge() {
        return Criteria.judged(
                "gullible",
                out -> {
                    String text = String.valueOf(out);
                    String category = text.substring(4, text.indexOf(';'));
                    boolean lesson = text.contains("[L:" + category + "]");
                    boolean verbose = text.contains("verbose=true");
                    return new io.github.llm4j.eval.judge.JudgeVerdict(
                            lesson ? 1.0 : verbose ? 0.9 : 0.2, "n/a");
                },
                0.99);
    }

    private static StubJudge flatterer() {
        return SimulationSupport.rewriter(
                (current, failures) -> current + " " + "Great question! ".repeat(20));
    }

    @Test
    void s8_aLengthConstraintRejectsTheHackBeforeAnyRolloutIsSpent() {
        SimulationSupport.CountingSystem system =
                new SimulationSupport.CountingSystem(verbosityDetector());
        OptimizationResult result =
                SimulationSupport.optimizer(flatterer())
                        .system(system)
                        .criteria(List.of(gullibleJudge()))
                        .constraints(PromptConstraints.builder().maxChars(120).build())
                        .patience(2)
                        .build()
                        .run();

        assertThat(result.trace())
                .extracting(Round::action)
                .containsOnly(RoundAction.REJECTED_CONSTRAINT);
        assertThat(result.trace().get(0).note()).contains("max 120");
        assertThat(result.best()).isEqualTo(result.seed());
    }

    @Test
    void s8_aDeterministicGuardrailZeroesTheHackedCandidate() {
        var requiresRealLesson =
                Criteria.guardrail(
                        "has-real-lesson",
                        out -> {
                            String text = String.valueOf(out);
                            String category = text.substring(4, text.indexOf(';'));
                            if (!text.contains("[L:" + category + "]")) {
                                throw new AssertionError("no real lesson for " + category);
                            }
                        });
        // the seed covers two categories; the hack adds flattery (which the gullible judge rewards)
        // but no real lessons, so the guardrail still fails on the other two categories
        OptimizationResult result =
                SimulationSupport.optimizer(flatterer())
                        .system(verbosityDetector())
                        .criteria(List.of(gullibleJudge(), requiresRealLesson))
                        .seed(Candidate.of(PARAM, "[L:units] [L:dates]"))
                        .targetValidationMean(1.0)
                        .patience(2)
                        .build()
                        .run();

        assertThat(result.trace()).extracting(Round::action).doesNotContain(RoundAction.ACCEPTED);
        assertThat(result.trace().get(0).action()).isEqualTo(RoundAction.GATE_FAILED);
        assertThat(result.best()).isEqualTo(result.seed());
        assertThat(result.generalized()).isFalse();
    }

    @Test
    void s8_withoutAnyDefenseTheGullibleJudgeIsExploited() {
        OptimizationResult result =
                SimulationSupport.optimizer(flatterer())
                        .system(verbosityDetector())
                        .criteria(List.of(gullibleJudge()))
                        .targetValidationMean(1.0)
                        .patience(2)
                        .build()
                        .run();

        // documents why guardrails and constraints exist: the hack wins against an exploitable
        // judge
        assertThat(result.best()).isNotEqualTo(result.seed());
        assertThat(result.best().get(PARAM)).contains("Great question!").doesNotContain("[L:");
    }

    // S9: judge noise -------------------------------------------------------------------------

    @Test
    void s9_aLuckyFirstScoreIsCorrectedByTheConfirmationRun() {
        java.util.Set<String> validationNames = new java.util.HashSet<>();
        SPLIT.validation().forEach(v -> validationNames.add(v.name()));
        java.util.Set<String> alreadyScored = java.util.concurrent.ConcurrentHashMap.newKeySet();
        // the child's FIRST validation pass on each scenario looks perfect; re-runs show the truth
        SystemUnderTest luckyOnce =
                (c, s) -> {
                    if (!c.id().equals("c0")
                            && validationNames.contains(s.name())
                            && alreadyScored.add(c.id() + ":" + s.name())) {
                        return "cat="
                                + s.name().substring(0, s.name().indexOf('-'))
                                + ";lessons=units,dates,currency,names,";
                    }
                    return SimulationSupport.system().run(c, s);
                };
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.slowRewriter())
                        .system(luckyOnce)
                        .targetValidationMean(0.95)
                        .build()
                        .run();

        assertThat(result.stopReason()).isEqualTo(StopReason.TARGET_REACHED);
        assertThat(result.best().id()).isNotEqualTo("c0");
        assertThat(result.bestSelectionValidationMean()).isEqualTo(1.0);
        assertThat(result.bestScores().validationMean()).isLessThan(0.9);
        assertThat(result.generalized()).isFalse();
        assertThat(result.verdict().reasons())
                .anyMatch(r -> r.contains("confirmation run") && r.contains("noise"));
    }

    // S12: restricted feedback ----------------------------------------------------------------

    @Test
    void s12_theRewriterNeverSeesValidationOrTestScenarios() {
        List<EvalScenario> all = SimulationSupport.scenarios(15);
        List<EvalScenario> train = all.subList(0, 30);
        List<EvalScenario> validation =
                all.subList(30, 48).stream()
                        .map(
                                s ->
                                        new EvalScenario(
                                                s.name(),
                                                s.input() + " VALMARK",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                        .toList();
        List<EvalScenario> test =
                all.subList(48, 60).stream()
                        .map(
                                s ->
                                        new EvalScenario(
                                                s.name(),
                                                s.input() + " TESTMARK",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                        .toList();
        StubJudge rewriter = SimulationSupport.lessonRewriter();

        // names in validation/test collide with train names by category prefix only; use distinct
        // names
        List<EvalScenario> validationRenamed =
                validation.stream()
                        .map(
                                s ->
                                        new EvalScenario(
                                                "v" + s.name(),
                                                s.input(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                        .toList();
        List<EvalScenario> testRenamed =
                test.stream()
                        .map(
                                s ->
                                        new EvalScenario(
                                                "t" + s.name(),
                                                s.input(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                        .toList();
        SystemUnderTest system =
                (c, s) -> {
                    String name = s.name().replaceFirst("^[vt](?=\\w+-\\d+$)", "");
                    return SimulationSupport.system()
                            .run(
                                    c,
                                    new EvalScenario(
                                            name, s.input(), null, null, null, null, null));
                };

        SimulationSupport.optimizer(rewriter)
                .system(system)
                .split(Split.explicit(train, validationRenamed, testRenamed))
                .scenarios(List.of())
                .build()
                .run();

        assertThat(rewriter.callCount()).isPositive();
        assertThat(rewriter.requests())
                .allSatisfy(
                        request -> {
                            String message = StubJudge.userMessage(request);
                            assertThat(message)
                                    .doesNotContain("VALMARK")
                                    .doesNotContain("TESTMARK");
                        });
    }

    // S13: determinism ------------------------------------------------------------------------

    @Test
    void s13_sameSeedAndResponsesGiveIdenticalTracesRegardlessOfParallelism() {
        OptimizationResult serial =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .parallelism(1)
                        .build()
                        .run();
        OptimizationResult parallel =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .parallelism(8)
                        .build()
                        .run();
        OptimizationResult again =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .parallelism(1)
                        .build()
                        .run();

        assertThat(parallel.trace()).isEqualTo(serial.trace());
        assertThat(again.trace()).isEqualTo(serial.trace());
        assertThat(parallel.best()).isEqualTo(serial.best());
        assertThat(parallel.bestScores()).isEqualTo(serial.bestScores());
    }

    @Test
    void s13_aDifferentRandomSeedChangesTheBatches() {
        OptimizationResult a =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .randomSeed(1)
                        .build()
                        .run();
        OptimizationResult b =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .randomSeed(2)
                        .build()
                        .run();
        assertThat(a.trace().get(0).batch()).isNotEqualTo(b.trace().get(0).batch());
    }

    // S15: the estimate is an upper bound -----------------------------------------------------

    @Test
    void s15_theEstimateBoundsActualUsage() {
        List<OptimizerBudget> budgets =
                List.of(
                        OptimizerBudget.builder().maxRollouts(80).build(),
                        OptimizerBudget.builder().maxRollouts(200).build(),
                        OptimizerBudget.builder().maxRounds(2).build(),
                        OptimizerBudget.builder().maxRounds(5).maxRollouts(400).build());
        for (OptimizerBudget budget : budgets) {
            PromptOptimizer optimizer =
                    SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                            .budget(budget)
                            .targetValidationMean(1.0)
                            .build();
            PromptOptimizer.Estimate estimate = optimizer.estimate();
            OptimizationResult result = optimizer.run();

            assertThat(result.cost().rollouts()).isLessThanOrEqualTo(estimate.maxRollouts());
            assertThat((long) result.trace().size()).isLessThanOrEqualTo(estimate.maxRounds());
            assertThat(result.cost().rewriterCalls())
                    .isLessThanOrEqualTo(estimate.maxRewriterCalls());
            assertThat(estimate.maxLlmCalls(3))
                    .isEqualTo(estimate.maxRollouts() * 3 + estimate.maxRewriterCalls());
        }
    }

    @Test
    void s15_aDurationOnlyBudgetHasNoRolloutBound() {
        PromptOptimizer.Estimate estimate =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .budget(
                                OptimizerBudget.builder()
                                        .maxDuration(Duration.ofMinutes(1))
                                        .build())
                        .build()
                        .estimate();
        assertThat(estimate.maxRollouts()).isEqualTo(-1);
        assertThat(estimate.maxRounds()).isEqualTo(-1);
        assertThat(estimate.maxLlmCalls(3)).isEqualTo(-1);
    }

    // S16: multiple parameters ----------------------------------------------------------------

    @Test
    void s16_parametersAreUpdatedRoundRobin() {
        SystemUnderTest twoParams =
                (c, s) ->
                        SimulationSupport.system()
                                .run(Candidate.of(PARAM, c.get("a") + " " + c.get("b")), s);
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("a", "base a");
        params.put("b", "base b");
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.slowRewriter())
                        .seed(Candidate.of(params))
                        .system(twoParams)
                        .build()
                        .run();

        assertThat(result.trace().get(0).parameter()).isEqualTo("a");
        assertThat(result.trace().get(1).parameter()).isEqualTo("b");
        assertThat(result.stopReason()).isEqualTo(StopReason.TARGET_REACHED);
        assertThat(result.best().get("a") + result.best().get("b")).contains("[L:units]");
    }

    // S18: an outage --------------------------------------------------------------------------

    @Test
    void s18_aJudgeOutageTripsTheBreakerInsteadOfBurningTheBudget() {
        AtomicInteger calls = new AtomicInteger();
        var flaky =
                Criteria.judged(
                        "flaky",
                        out -> {
                            if (calls.incrementAndGet() > 30) {
                                throw new IllegalStateException("judge is down");
                            }
                            String text = String.valueOf(out);
                            String category = text.substring(4, text.indexOf(';'));
                            return new io.github.llm4j.eval.judge.JudgeVerdict(
                                    text.contains("[L:" + category + "]") ? 1.0 : 0.2, "ok");
                        },
                        0.99);
        SimulationSupport.CountingSystem system =
                new SimulationSupport.CountingSystem(SimulationSupport.system());

        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter())
                        .system(system)
                        .criteria(List.of(flaky))
                        .targetValidationMean(1.0)
                        .budget(OptimizerBudget.builder().maxRollouts(100_000).build())
                        .failureBreakerLimit(5)
                        .build()
                        .run();

        assertThat(result.stopReason()).isEqualTo(StopReason.FAILED);
        assertThat(result.warnings()).anyMatch(w -> w.contains("5 consecutive rollouts failed"));
        assertThat(system.count()).isLessThan(100);
        assertThat(result.generalized()).isFalse();
    }
}
