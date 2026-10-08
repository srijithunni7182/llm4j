package io.github.llm4j.eval.optimize;

import io.github.llm4j.eval.criteria.Criterion;
import io.github.llm4j.eval.criteria.Scorecard;
import io.github.llm4j.eval.criteria.Scoring;
import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;

/**
 * Runs a candidate on scenarios and scores the outputs. Rollouts run in parallel on the supplied
 * executor (or sequentially when it is null) and results are collected <em>by index</em>, so the
 * outcome does not depend on completion order. Budget is charged by the caller before invoking.
 */
final class Evaluator {

    private final SystemUnderTest system;
    private final List<Criterion> criteria;
    private final ExecutorService pool;
    private final int feedbackChars;
    private final UnaryOperator<String> redactor;

    Evaluator(
            SystemUnderTest system,
            List<Criterion> criteria,
            ExecutorService pool,
            int feedbackChars,
            UnaryOperator<String> redactor) {
        this.system = system;
        this.criteria = List.copyOf(criteria);
        this.pool = pool;
        this.feedbackChars = feedbackChars;
        this.redactor = redactor;
    }

    List<Scorecard> evaluate(Candidate candidate, List<EvalScenario> scenarios) {
        if (pool == null || scenarios.size() < 2) {
            List<Scorecard> cards = new ArrayList<>(scenarios.size());
            for (EvalScenario scenario : scenarios) {
                cards.add(rollout(candidate, scenario));
            }
            return cards;
        }
        List<Future<Scorecard>> futures = new ArrayList<>(scenarios.size());
        for (EvalScenario scenario : scenarios) {
            futures.add(pool.submit(() -> rollout(candidate, scenario)));
        }
        List<Scorecard> cards = new ArrayList<>(scenarios.size());
        for (Future<Scorecard> future : futures) {
            try {
                cards.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                throw new IllegalStateException("interrupted while evaluating " + candidate, e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("rollout failed unexpectedly", e.getCause());
            }
        }
        return cards;
    }

    private Scorecard rollout(Candidate candidate, EvalScenario scenario) {
        Scorecard card;
        try {
            Object output = system.run(candidate, scenario);
            card = Scoring.score(scenario, output, criteria, feedbackChars);
        } catch (RuntimeException e) {
            card = Scoring.systemFailure(scenario, e);
        }
        return redact(card);
    }

    private Scorecard redact(Scorecard card) {
        if (redactor == null) {
            return card;
        }
        return new Scorecard(
                card.scenario(),
                card.score(),
                card.guardrailViolated(),
                card.outcomes(),
                redactor.apply(card.output()),
                redactor.apply(card.feedback()),
                card.infrastructureFailure());
    }

    static double mean(List<Scorecard> cards) {
        return cards.stream().mapToDouble(Scorecard::score).average().orElse(0.0);
    }
}
