package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.criteria.Criteria;
import io.github.llm4j.eval.criteria.Scorecard;
import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvaluatorTest {

    private final Candidate candidate = Candidate.of("p", "prompt");
    private final List<EvalScenario> scenarios = SplitTest.scenarios(12);

    private static io.github.llm4j.eval.criteria.Criterion lengthCriterion() {
        return Criteria.judged(
                "even-index",
                out ->
                        new io.github.llm4j.eval.judge.JudgeVerdict(
                                Integer.parseInt(String.valueOf(out).replace("out-", "")) % 2 == 0
                                        ? 1.0
                                        : 0.0,
                                "n/a"),
                0.5);
    }

    @Test
    void resultsAreIndexOrderedWhateverTheParallelism() {
        SystemUnderTest system =
                (c, s) -> {
                    // finish in reverse order to prove collection is by index, not completion
                    sleepQuietly((12 - Integer.parseInt(s.name().substring(1))) * 3L);
                    return "out-" + s.name().substring(1);
                };
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Scorecard> parallel =
                    new Evaluator(system, List.of(lengthCriterion()), pool, 400, null)
                            .evaluate(candidate, scenarios);
            List<Scorecard> serial =
                    new Evaluator(system, List.of(lengthCriterion()), null, 400, null)
                            .evaluate(candidate, scenarios);

            assertThat(parallel).isEqualTo(serial);
            assertThat(parallel)
                    .extracting(Scorecard::scenario)
                    .containsExactlyElementsOf(
                            scenarios.stream().map(EvalScenario::toString).toList());
            assertThat(Evaluator.mean(parallel)).isEqualTo(0.5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void eachScenarioRunsExactlyOnce() {
        ConcurrentHashMap<String, AtomicInteger> runs = new ConcurrentHashMap<>();
        SystemUnderTest system =
                (c, s) -> {
                    runs.computeIfAbsent(s.name(), k -> new AtomicInteger()).incrementAndGet();
                    return "out-0";
                };
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            new Evaluator(system, List.of(), pool, 400, null).evaluate(candidate, scenarios);
        } finally {
            pool.shutdownNow();
        }
        assertThat(runs).hasSize(12).allSatisfy((k, v) -> assertThat(v.get()).isEqualTo(1));
    }

    @Test
    void aThrowingSystemBecomesAnInfrastructureZeroAndOthersContinue() {
        SystemUnderTest system =
                (c, s) -> {
                    if (s.name().equals("s3")) {
                        throw new IllegalStateException("agent crashed");
                    }
                    return "out-0";
                };
        List<Scorecard> cards =
                new Evaluator(system, List.of(lengthCriterion()), null, 400, null)
                        .evaluate(candidate, scenarios);

        assertThat(cards.get(3).score()).isZero();
        assertThat(cards.get(3).infrastructureFailure()).isTrue();
        assertThat(cards.get(3).feedback()).contains("agent crashed");
        assertThat(cards.get(4).score()).isEqualTo(1.0);
    }

    @Test
    void theRedactorScrubsOutputAndFeedback() {
        SystemUnderTest system = (c, s) -> "secret-token-123";
        var failing =
                Criteria.assertion(
                        "no-secret",
                        out -> {
                            throw new AssertionError("found secret-token-123");
                        });
        List<Scorecard> cards =
                new Evaluator(
                                system,
                                List.of(failing),
                                null,
                                400,
                                text -> text.replace("secret-token-123", "[REDACTED]"))
                        .evaluate(candidate, scenarios.subList(0, 2));

        assertThat(cards)
                .allSatisfy(
                        card -> {
                            assertThat(card.output()).isEqualTo("[REDACTED]");
                            assertThat(card.feedback())
                                    .contains("[REDACTED]")
                                    .doesNotContain("secret-token-123");
                        });
    }

    @Test
    void meanOfNothingIsZero() {
        assertThat(Evaluator.mean(List.of())).isZero();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
