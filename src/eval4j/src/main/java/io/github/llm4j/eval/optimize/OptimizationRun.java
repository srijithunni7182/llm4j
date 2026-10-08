package io.github.llm4j.eval.optimize;

import io.github.llm4j.eval.criteria.Scorecard;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.report.EvalRecorder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One execution of the optimization loop (GEPA-style: reflect on training failures, keep a Pareto
 * pool selected on validation, verify on a sealed test split). Single-use: create, {@link
 * #execute()}, discard. Decisions happen on the calling thread in order; only rollouts run in
 * parallel, and their results are collected by index, so a run is deterministic given deterministic
 * rollouts.
 */
final class OptimizationRun {

    /** Thrown to unwind the current round when the run must stop. */
    private static final class StopSignal extends RuntimeException {
        private final transient StopReason reason;

        StopSignal(StopReason reason, String message) {
            super(message, null, false, false);
            this.reason = reason;
        }
    }

    private final OptimizerConfig cfg;
    private final BudgetTracker budget;
    private final Checkpointer checkpointer;
    private final CandidatePool pool = new CandidatePool();
    private final List<Round> trace = new ArrayList<>();
    private final Set<Candidate> proposed = new HashSet<>();
    private final List<String> warnings = new ArrayList<>();

    private Evaluator evaluator;
    private Rewriter rewriter;
    private List<Scorecard> seedValidation = List.of();
    private int completedRounds;
    private int noProgress;
    private int consecutiveInfraFailures;
    private int nextId = 1;
    private String failureMessage;

    OptimizationRun(OptimizerConfig cfg) {
        this.cfg = cfg;
        this.budget = new BudgetTracker(cfg.budget(), cfg.clock(), cfg.counters());
        this.checkpointer =
                cfg.checkpointDir() == null ? null : new Checkpointer(cfg.checkpointDir());
        this.warnings.addAll(cfg.warnings());
    }

    OptimizationResult execute() {
        ExecutorService threads =
                cfg.parallelism() > 1 ? Executors.newFixedThreadPool(cfg.parallelism()) : null;
        try {
            evaluator =
                    new Evaluator(
                            cfg.system(),
                            cfg.criteria(),
                            threads,
                            cfg.feedbackChars(),
                            cfg.redactor());
            rewriter = new Rewriter(cfg.rewriter(), budget, cfg.rewriterTemperature());
            cfg.warnings().forEach(w -> notifyWarning(w));

            StopReason stop = start();
            if (stop == null) {
                stop = loop();
            }
            notifyStop(stop);
            return finish(stop);
        } finally {
            if (threads != null) {
                threads.shutdownNow();
            }
        }
    }

    // --- start: resume or score the seed ----------------------------------------------------

    /** Returns a stop reason if the run cannot even start, otherwise null. */
    private StopReason start() {
        Optional<CheckpointState> saved =
                checkpointer == null ? Optional.empty() : checkpointer.load();
        if (saved.isPresent()) {
            restore(saved.get());
            return null;
        }
        if (!budget.tryAcquireRollouts(cfg.split().validation().size(), cfg.finalPhaseReserve())) {
            return fail("the budget is too small to score the seed on the validation split");
        }
        seedValidation = evaluator.evaluate(cfg.seed(), cfg.split().validation());
        if (seedValidation.stream().allMatch(Scorecard::infrastructureFailure)) {
            return fail(
                    "the seed could not be scored on any validation scenario: "
                            + seedValidation.get(0).feedback());
        }
        pool.add(cfg.seed(), scores(seedValidation), anyGuardrail(seedValidation));
        proposed.add(cfg.seed());
        save();
        return null;
    }

    private void restore(CheckpointState state) {
        if (!state.fingerprint().equals(cfg.fingerprint())) {
            throw new OptimizerConfigurationException(
                    "the checkpoint in "
                            + cfg.checkpointDir()
                            + " belongs to a different run (the seed, scenarios, criteria, split or"
                            + " settings changed). Delete "
                            + Checkpointer.FILE_NAME
                            + " to start over, or point checkpointDir somewhere else.");
        }
        completedRounds = state.completedRounds();
        noProgress = state.noProgress();
        consecutiveInfraFailures = state.consecutiveInfraFailures();
        nextId = state.nextId();
        seedValidation = state.seedValidation();
        for (CheckpointState.EntryState entry : state.entries()) {
            pool.add(
                    entry.candidate().toCandidate(),
                    entry.validationScores(),
                    entry.guardrailViolation());
        }
        for (Map<String, String> params : state.proposed()) {
            proposed.add(Candidate.of(params));
        }
        trace.addAll(state.trace());
        budget.restore(state.rollouts(), state.rewriterCalls(), state.elapsedMillis());
    }

    private void save() {
        if (checkpointer == null) {
            return;
        }
        List<CheckpointState.EntryState> entries = new ArrayList<>();
        for (CandidatePool.Entry entry : pool.entries()) {
            entries.add(
                    new CheckpointState.EntryState(
                            CandidateSnapshot.of(entry.candidate()),
                            entry.validationScores(),
                            entry.guardrailViolation()));
        }
        List<Map<String, String>> proposedParams = new ArrayList<>();
        proposed.forEach(c -> proposedParams.add(c.parameters()));
        checkpointer.save(
                new CheckpointState(
                        cfg.fingerprint(),
                        completedRounds,
                        noProgress,
                        consecutiveInfraFailures,
                        nextId,
                        budget.rollouts(),
                        budget.rewriterCalls(),
                        budget.elapsedMillis(),
                        seedValidation,
                        entries,
                        proposedParams,
                        trace));
    }

    // --- the loop ---------------------------------------------------------------------------

    private StopReason loop() {
        while (true) {
            StopReason stop = checkStop();
            if (stop != null) {
                return stop;
            }
            try {
                Round round = runRound(completedRounds + 1);
                completedRounds++;
                trace.add(round);
                save();
                notifyRound(round);
                recordMetric(round);
            } catch (StopSignal signal) {
                if (signal.reason == StopReason.FAILED) {
                    failureMessage = signal.getMessage();
                }
                return signal.reason;
            }
        }
    }

    private StopReason checkStop() {
        CandidatePool.Entry best = pool.best();
        if (best.mean() >= cfg.target() && !best.guardrailViolation()) {
            return StopReason.TARGET_REACHED;
        }
        if (Thread.currentThread().isInterrupted()) {
            return StopReason.CANCELLED;
        }
        Optional<StopReason> exhausted = budget.exhausted(completedRounds);
        if (exhausted.isPresent()) {
            return exhausted.get();
        }
        if (noProgress >= cfg.patience()) {
            return StopReason.NO_PROGRESS;
        }
        return null;
    }

    private Round runRound(int index) {
        SplittableRandom random =
                new SplittableRandom(cfg.randomSeed() * 0x9E3779B97F4A7C15L + index);
        Candidate parent = pool.pick(random);
        List<String> names = new ArrayList<>(cfg.seed().parameterNames());
        String parameter = names.get((index - 1) % names.size());
        List<EvalScenario> batch = sample(cfg.split().train(), cfg.effectiveBatchSize(), random);
        List<String> batchNames = batch.stream().map(EvalScenario::toString).toList();

        List<Scorecard> parentCards = evaluate(parent, batch);
        double parentMean = Evaluator.mean(parentCards);
        if (parentCards.stream().allMatch(c -> c.score() >= cfg.perfectThreshold())) {
            noProgress++;
            return round(
                    index,
                    parent,
                    parameter,
                    RoundAction.SKIPPED_PERFECT,
                    null,
                    batchNames,
                    parentMean,
                    null,
                    null,
                    false,
                    "the parent already scores perfectly on this batch");
        }

        String proposal;
        try {
            proposal =
                    rewriter.propose(
                            parameter,
                            cfg.parameterDescriptions().get(parameter),
                            parent.get(parameter),
                            examples(parentCards, batch));
        } catch (RewriteFailedException e) {
            noProgress++;
            return round(
                    index,
                    parent,
                    parameter,
                    RoundAction.REWRITE_FAILED,
                    null,
                    batchNames,
                    parentMean,
                    null,
                    null,
                    false,
                    e.getMessage());
        }

        Candidate child = parent.derive("c" + nextId, parameter, proposal, index);
        String violation = cfg.constraints().violation(child);
        if (violation != null) {
            noProgress++;
            return round(
                    index,
                    parent,
                    parameter,
                    RoundAction.REJECTED_CONSTRAINT,
                    null,
                    batchNames,
                    parentMean,
                    null,
                    null,
                    false,
                    violation);
        }
        if (!proposed.add(child)) {
            noProgress++;
            return round(
                    index,
                    parent,
                    parameter,
                    RoundAction.REJECTED_DUPLICATE,
                    null,
                    batchNames,
                    parentMean,
                    null,
                    null,
                    false,
                    "the proposal equals a candidate already tried");
        }
        String childId = "c" + nextId++;

        List<Scorecard> childCards = evaluate(child, batch);
        double childMean = Evaluator.mean(childCards);
        if (childMean <= parentMean + cfg.gateMargin()) {
            noProgress++;
            return round(
                    index,
                    parent,
                    parameter,
                    RoundAction.GATE_FAILED,
                    childId,
                    batchNames,
                    parentMean,
                    childMean,
                    null,
                    false,
                    "the child did not beat its parent on the batch");
        }

        List<Scorecard> validation = evaluate(child, cfg.split().validation());
        pool.add(child, scores(validation), anyGuardrail(validation));
        boolean onFrontier = pool.onFrontier(child);
        noProgress = onFrontier ? 0 : noProgress + 1;
        return round(
                index,
                parent,
                parameter,
                RoundAction.ACCEPTED,
                childId,
                batchNames,
                parentMean,
                childMean,
                Evaluator.mean(validation),
                onFrontier,
                onFrontier ? "" : "accepted into the pool but not on the Pareto frontier");
    }

    private Round round(
            int index,
            Candidate parent,
            String parameter,
            RoundAction action,
            String childId,
            List<String> batch,
            double parentMean,
            Double childMean,
            Double validationMean,
            boolean onFrontier,
            String note) {
        return new Round(
                index,
                parent.id(),
                parameter,
                action,
                childId,
                batch,
                parentMean,
                childMean,
                validationMean,
                onFrontier,
                pool.size(),
                pool.frontierSize(),
                note);
    }

    /**
     * Charges the budget, then runs the evaluation; throws {@link StopSignal} when out of budget.
     */
    private List<Scorecard> evaluate(Candidate candidate, List<EvalScenario> scenarios) {
        if (!budget.tryAcquireRollouts(scenarios.size(), cfg.finalPhaseReserve())) {
            throw new StopSignal(StopReason.MAX_ROLLOUTS, "rollout budget exhausted");
        }
        List<Scorecard> cards = evaluator.evaluate(candidate, scenarios);
        for (Scorecard card : cards) {
            consecutiveInfraFailures =
                    card.infrastructureFailure() ? consecutiveInfraFailures + 1 : 0;
            if (consecutiveInfraFailures >= cfg.failureBreakerLimit()) {
                throw new StopSignal(
                        StopReason.FAILED,
                        consecutiveInfraFailures
                                + " consecutive rollouts failed for infrastructure reasons (last: "
                                + card.feedback()
                                + ")");
            }
        }
        return cards;
    }

    private List<Rewriter.Example> examples(List<Scorecard> cards, List<EvalScenario> batch) {
        List<Integer> failing = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).score() < cfg.perfectThreshold()) {
                failing.add(i);
            }
        }
        failing.sort((a, b) -> Double.compare(cards.get(a).score(), cards.get(b).score()));
        List<Rewriter.Example> examples = new ArrayList<>();
        for (int i : failing.subList(0, Math.min(failing.size(), cfg.maxFailuresInPrompt()))) {
            Scorecard card = cards.get(i);
            examples.add(
                    new Rewriter.Example(
                            batch.get(i).input(), card.output(), card.score(), card.feedback()));
        }
        return examples;
    }

    private static List<EvalScenario> sample(
            List<EvalScenario> from, int size, SplittableRandom random) {
        List<EvalScenario> copy = new ArrayList<>(from);
        for (int i = copy.size() - 1; i > 0; i--) { // Fisher-Yates with the seeded stream
            Collections.swap(copy, i, random.nextInt(i + 1));
        }
        return copy.subList(0, Math.min(size, copy.size()));
    }

    // --- finish: confirmation, test, verdict ------------------------------------------------

    private OptimizationResult finish(StopReason stop) {
        CandidatePool.Entry best = pool.best();
        Candidate bestCandidate = best == null ? cfg.seed() : best.candidate();
        boolean bestIsSeed = bestCandidate.equals(cfg.seed());
        double selectionMean = best == null ? 0.0 : best.mean();
        List<String> problems = new ArrayList<>();

        if (stop == StopReason.FAILED || stop == StopReason.CANCELLED) {
            String why =
                    stop == StopReason.CANCELLED
                            ? "the run was cancelled"
                            : failureMessage != null ? failureMessage : "the run failed";
            warnings.add(why);
            problems.add(why + ", so nothing was verified");
            return result(
                    stop,
                    bestCandidate,
                    selectionMean,
                    seedOnlyScores(),
                    seedOnlyScores(),
                    null,
                    problems);
        }

        List<Scorecard> seedTest = List.of();
        List<Scorecard> bestValidation = seedValidation;
        List<Scorecard> bestTest = List.of();
        boolean hasTest = !cfg.split().test().isEmpty();
        boolean confirmed = true;

        if (!bestIsSeed) {
            if (budget.tryAcquireRollouts(cfg.split().validation().size(), 0)) {
                bestValidation = evaluator.evaluate(bestCandidate, cfg.split().validation());
            } else {
                confirmed = false;
                bestValidation = List.of();
                problems.add("no budget was left for the confirmation run");
            }
        }
        if (hasTest) {
            if (budget.tryAcquireRollouts(cfg.split().test().size(), 0)) {
                seedTest = evaluator.evaluate(cfg.seed(), cfg.split().test());
            }
            if (bestIsSeed) {
                bestTest = seedTest;
            } else if (budget.tryAcquireRollouts(cfg.split().test().size(), 0)) {
                bestTest = evaluator.evaluate(bestCandidate, cfg.split().test());
            }
        }

        CandidateScores seedScores = scoresFor(seedValidation, seedTest);
        CandidateScores bestScores = bestIsSeed ? seedScores : scoresFor(bestValidation, bestTest);
        Comparison comparison =
                hasTest && !bestIsSeed && !seedTest.isEmpty() && !bestTest.isEmpty()
                        ? Comparison.of(seedTest, bestTest)
                        : null;
        judge(
                problems,
                bestIsSeed,
                hasTest,
                confirmed,
                selectionMean,
                seedScores,
                bestScores,
                bestTest,
                comparison);
        return result(
                stop, bestCandidate, selectionMean, seedScores, bestScores, comparison, problems);
    }

    /** Fills {@code problems} with every reason the result cannot be trusted. */
    private void judge(
            List<String> problems,
            boolean bestIsSeed,
            boolean hasTest,
            boolean confirmed,
            double selectionMean,
            CandidateScores seedScores,
            CandidateScores bestScores,
            List<Scorecard> bestTest,
            Comparison comparison) {
        if (bestIsSeed) {
            problems.add("no candidate improved on the seed");
            return;
        }
        if (!hasTest) {
            problems.add("there is no test split, so generalization could not be checked");
        }
        if (confirmed && selectionMean - bestScores.validationMean() > cfg.maxOverfitGap()) {
            problems.add(
                    String.format(
                            Locale.ROOT,
                            "the selection-time validation mean %.3f fell to %.3f in the confirmation run"
                                    + " (more than %.2f): the win was probably noise",
                            selectionMean,
                            bestScores.validationMean(),
                            cfg.maxOverfitGap()));
        }
        if (!hasTest || bestScores.testMean() == null || seedScores.testMean() == null) {
            return;
        }
        double gain = bestScores.testMean() - seedScores.testMean();
        if (gain < cfg.minTestGain()) {
            problems.add(
                    String.format(
                            Locale.ROOT,
                            "the test-split gain %.3f is below the required %.3f (best %.3f vs seed %.3f)",
                            gain,
                            cfg.minTestGain(),
                            bestScores.testMean(),
                            seedScores.testMean()));
        }
        long violations = bestTest.stream().filter(Scorecard::guardrailViolated).count();
        if (violations > 0) {
            problems.add(violations + " test scenario(s) violate a guardrail");
        }
        if (confirmed
                && bestScores.validationMean() - bestScores.testMean() > cfg.maxOverfitGap()) {
            problems.add(
                    String.format(
                            Locale.ROOT,
                            "validation %.3f exceeds test %.3f by more than %.2f: a sign of overfitting",
                            bestScores.validationMean(),
                            bestScores.testMean(),
                            cfg.maxOverfitGap()));
        }
        if (comparison != null && comparison.seedWins() > comparison.bestWins()) {
            problems.add(
                    "the seed beats the best candidate on more test scenarios ("
                            + comparison.seedWins()
                            + " vs "
                            + comparison.bestWins()
                            + ")");
        }
    }

    private OptimizationResult result(
            StopReason stop,
            Candidate best,
            double selectionMean,
            CandidateScores seedScores,
            CandidateScores bestScores,
            Comparison comparison,
            List<String> problems) {
        Verdict verdict;
        if (problems.isEmpty()) {
            verdict =
                    new Verdict(
                            true,
                            List.of(
                                    String.format(
                                            Locale.ROOT,
                                            "test mean %.3f vs seed %.3f (+%.3f); confirmation validation"
                                                    + " %.3f; no guardrail violations",
                                            bestScores.testMean(),
                                            seedScores.testMean(),
                                            bestScores.testMean() - seedScores.testMean(),
                                            bestScores.validationMean())));
        } else {
            verdict = new Verdict(false, problems);
        }
        Cost cost =
                new Cost(
                        budget.rollouts(),
                        budget.rewriterCalls(),
                        budget.llmCalls() - budget.rewriterCalls(),
                        budget.elapsedMillis());
        OptimizationResult result =
                new OptimizationResult(
                        cfg.seed(),
                        best,
                        stop,
                        seedScores,
                        bestScores,
                        selectionMean,
                        verdict,
                        comparison,
                        cost,
                        trace,
                        warnings);
        OptimizationExporter.export(result);
        return result;
    }

    private CandidateScores seedOnlyScores() {
        return scoresFor(seedValidation, List.of());
    }

    private static CandidateScores scoresFor(List<Scorecard> validation, List<Scorecard> test) {
        return new CandidateScores(
                Evaluator.mean(validation),
                validation,
                test.isEmpty() ? null : Evaluator.mean(test),
                test);
    }

    // --- helpers ----------------------------------------------------------------------------

    private StopReason fail(String message) {
        failureMessage = message;
        return StopReason.FAILED;
    }

    private static double[] scores(List<Scorecard> cards) {
        return cards.stream().mapToDouble(Scorecard::score).toArray();
    }

    private static boolean anyGuardrail(List<Scorecard> cards) {
        return cards.stream().anyMatch(Scorecard::guardrailViolated);
    }

    private void notifyRound(Round round) {
        try {
            cfg.listener().onRound(round);
        } catch (RuntimeException e) {
            warnings.add("listener failed on round " + round.index() + ": " + e);
        }
    }

    private void notifyWarning(String message) {
        try {
            cfg.listener().onWarning(message);
        } catch (RuntimeException e) {
            // a broken listener must not stop the run
        }
    }

    private void notifyStop(StopReason reason) {
        try {
            cfg.listener().onStop(reason);
        } catch (RuntimeException e) {
            warnings.add("listener failed on stop: " + e);
        }
    }

    /** Feeds the existing reports/baselines: the best validation mean after each round. */
    private void recordMetric(Round round) {
        CandidatePool.Entry best = pool.best();
        EvalRecorder.record(
                "Optimizer: best validation",
                best.mean(),
                cfg.target(),
                "round " + round.index() + " " + round.action(),
                null);
    }
}
