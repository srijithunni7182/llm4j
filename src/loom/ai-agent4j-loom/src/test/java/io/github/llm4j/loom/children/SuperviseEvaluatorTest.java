package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.SuperviseStmt;
import io.github.llm4j.loom.execution.SuperviseEvaluator;
import io.github.llm4j.loom.execution.SuperviseEvaluator.Facts;
import io.github.llm4j.loom.execution.SuperviseEvaluator.Firing;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Phase 33e: the counted rules of {@code supervise} (D23, D26, D30), with no clock and no thread. */
class SuperviseEvaluatorTest {

    private static SuperviseStmt block(String rules) {
        return (SuperviseStmt)
                StartParseTest.parse("workflow W(q) { supervise work { " + rules + " } }")
                        .getWorkflows()
                        .get(0)
                        .getStatements()
                        .get(0);
    }

    private static Facts facts(long tokens, long calls, long elapsedMs, long idleMs) {
        return new Facts(tokens, calls, BigDecimal.ZERO, elapsedMs, idleMs);
    }

    private static List<String> fired(List<Firing> firings) {
        return firings.stream().filter(Firing::delivered).map(f -> f.action().kind().name()).toList();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "tokens > 100, 101, true",
        "tokens > 100, 100, false",
        "tokens >= 100, 100, true",
        "tokens < 100, 99, true",
        "tokens < 100, 100, false",
        "tokens <= 100, 100, true",
        "tokens == 100, 100, true",
        "tokens == 100, 101, false",
    })
    void everyComparisonOfACounter(String condition, long tokens, boolean fires) {
        var evaluator = new SuperviseEvaluator(block("when " + condition + " { wrap_up }"), Set.of());

        assertThat(fired(evaluator.evaluate(facts(tokens, 0, 0, 0)))).hasSize(fires ? 1 : 0);
    }

    @Test
    void everyCounterIsReadFromItsOwnFact() {
        var evaluator =
                new SuperviseEvaluator(
                        block(
                                """
                                when tokens > 10 { steer "a" }
                                when calls > 10 { steer "b" }
                                when cost > "$0.50" { steer "c" }
                                when elapsed > 5m { steer "d" }
                                when idle > 3m { steer "e" }
                                """),
                        Set.of());

        var tokens = evaluator.evaluate(new Facts(11, 0, BigDecimal.ZERO, 0, 0));
        assertThat(tokens).extracting(f -> f.action().text()).containsExactly("a");
        var rest =
                evaluator.evaluate(
                        new Facts(0, 11, new BigDecimal("0.51"), 5 * 60_000 + 1, 3 * 60_000 + 1));
        // steers share one agent slot a tick, so only the first of them is delivered; the rest say why
        assertThat(rest).hasSize(4);
        assertThat(rest.get(0).delivered()).isTrue();
        assertThat(rest.stream().skip(1)).allMatch(f -> !f.delivered());
    }

    @Test
    void aRuleFiresWhenItBecomesTrueAndAgainOnlyAfterItWentFalse() {
        var evaluator = new SuperviseEvaluator(block("when idle > 3m { steer \"Narrow.\" }"), Set.of());

        assertThat(fired(evaluator.evaluate(facts(0, 0, 0, 4 * 60_000)))).hasSize(1);
        assertThat(fired(evaluator.evaluate(facts(0, 0, 0, 5 * 60_000))))
                .as("still true: no second firing")
                .isEmpty();
        assertThat(fired(evaluator.evaluate(facts(0, 0, 0, 0)))).isEmpty(); // went false
        assertThat(fired(evaluator.evaluate(facts(0, 0, 0, 4 * 60_000))))
                .as("true again")
                .hasSize(1);
    }

    @Test
    void onceLimitsARuleToOneFiringEvenIfItGoesFalseAndTrueAgain() {
        var evaluator = new SuperviseEvaluator(block("when idle > 3m { steer \"Narrow.\" once }"), Set.of());

        assertThat(fired(evaluator.evaluate(facts(0, 0, 0, 4 * 60_000)))).hasSize(1);
        evaluator.evaluate(facts(0, 0, 0, 0));
        assertThat(fired(evaluator.evaluate(facts(0, 0, 0, 4 * 60_000)))).isEmpty();
    }

    @Test
    void aRuleThatFiredBeforeARestartDoesNotFireAgainWhileItStaysTrue() {
        var rules = block("when tokens > 10 { steer \"x\" }\nwhen calls > 10 { steer \"y\" once }");
        var evaluator = new SuperviseEvaluator(rules, Set.of(0, 1));

        assertThat(fired(evaluator.evaluate(facts(50, 50, 0, 0)))).isEmpty();
        evaluator.evaluate(facts(0, 0, 0, 0));
        assertThat(fired(evaluator.evaluate(facts(50, 50, 0, 0))))
                .as("the rule without once may fire again after going false and true; the once rule never")
                .hasSize(1);
    }

    @Test
    void aCancelEndsTheEvaluationOfTheTick() {
        var evaluator =
                new SuperviseEvaluator(
                        block(
                                """
                                when tokens > 10 { cancel use partial }
                                when tokens > 5 { steer "never delivered" }
                                """),
                        Set.of());

        var firings = evaluator.evaluate(facts(50, 0, 0, 0));

        assertThat(firings).hasSize(1);
        assertThat(firings.get(0).action().kind().name()).isEqualTo("CANCEL");
        assertThat(firings.get(0).endsEvaluation()).isTrue();
    }

    @Test
    void aWrapUpEndsTheEvaluationToo() {
        var evaluator =
                new SuperviseEvaluator(
                        block("when tokens > 10 { wrap_up }\nwhen tokens > 5 { steer \"x\" }"), Set.of());

        assertThat(evaluator.evaluate(facts(50, 0, 0, 0))).hasSize(1);
    }

    @Test
    void ruleActionsRunInTheOrderWritten() {
        var evaluator =
                new SuperviseEvaluator(
                        block("when tokens > 5 { steer \"first\" }\nwhen calls > 5 { steer \"second\" }"),
                        Set.of());

        var firings = evaluator.evaluate(facts(10, 10, 0, 0));

        assertThat(firings).extracting(f -> f.action().text()).containsExactly("first", "second");
    }

    @Test
    void twoSteersInOneTickDeliverOneAndRecordTheOtherAsSkipped() {
        var evaluator =
                new SuperviseEvaluator(
                        block("when tokens > 5 { steer \"first\" }\nwhen calls > 5 { steer \"second\" }"),
                        Set.of());

        var firings = evaluator.evaluate(facts(10, 10, 0, 0));

        assertThat(firings.get(0).delivered()).isTrue();
        assertThat(firings.get(1).delivered()).isFalse();
        assertThat(firings.get(1).outcome()).isEqualTo("skipped: already steered this tick");
    }

    @Test
    void theSteerSlotIsFreeAgainTheNextTick() {
        var evaluator =
                new SuperviseEvaluator(
                        block("when tokens > 5 { steer \"first\" }\nwhen calls > 5 { steer \"second\" }"),
                        Set.of());
        evaluator.evaluate(facts(10, 10, 0, 0));
        evaluator.evaluate(facts(0, 0, 0, 0));

        var again = evaluator.evaluate(facts(10, 0, 0, 0));

        assertThat(fired(again)).hasSize(1);
    }

    @Test
    void aRuleIndexIsTheOrderItIsWritten() {
        var evaluator =
                new SuperviseEvaluator(
                        block("when tokens > 5 { steer \"a\" }\nwhen calls > 5 { steer \"b\" }"), Set.of());

        var firings = evaluator.evaluate(facts(10, 10, 0, 0));

        assertThat(firings).extracting(Firing::rule).containsExactly(0, 1);
    }

    private static SuperviseEvaluator.Facts withTrajectory(long elapsed, java.util.Map<String, Integer> calls, java.util.List<String> order,
            java.util.Set<String> reached, java.util.Map<String, Integer> refused) {
        return new SuperviseEvaluator.Facts(0, 0, BigDecimal.ZERO, elapsed, 0,
                new io.github.llm4j.loom.execution.Trajectory.Snapshot(calls, order, reached, refused));
    }

    @Test
    void callsAtMostFiresOnlyPastTheLimitNotAtIt() {
        var e = new SuperviseEvaluator(block("assert calls Search at most 2 times { wrap_up }"), Set.of());
        assertThat(e.evaluate(withTrajectory(0, java.util.Map.of("Search", 2), java.util.List.of("Search"), Set.of(), java.util.Map.of()))).isEmpty();
        assertThat(e.evaluate(withTrajectory(0, java.util.Map.of("Search", 3), java.util.List.of("Search"), Set.of(), java.util.Map.of()))).hasSize(1);
    }

    @Test
    void callsBeforeIsViolatedByTheOtherComingFirstOrByTheFirstNeverComing() {
        var rules = "assert calls Plan before Write { wrap_up }";
        var rightOrder = new SuperviseEvaluator(block(rules), Set.of());
        assertThat(rightOrder.evaluate(withTrajectory(0, java.util.Map.of("Plan", 1, "Write", 1), java.util.List.of("Plan", "Write"), Set.of(), java.util.Map.of()))).isEmpty();
        var neverPlanned = new SuperviseEvaluator(block(rules), Set.of());
        assertThat(neverPlanned.evaluate(withTrajectory(0, java.util.Map.of("Write", 1), java.util.List.of("Write"), Set.of(), java.util.Map.of()))).hasSize(1);
        var notYet = new SuperviseEvaluator(block(rules), Set.of());
        assertThat(notYet.evaluate(withTrajectory(0, java.util.Map.of("Plan", 1), java.util.List.of("Plan"), Set.of(), java.util.Map.of()))).isEmpty();
    }

    @Test
    void reachesWithinIsViolatedOnlyPastTheDeadlineNotAtIt() {
        var e = new SuperviseEvaluator(block("assert reaches \"collected\" within 5m { wrap_up }"), Set.of());
        assertThat(e.evaluate(withTrajectory(300_000, java.util.Map.of(), java.util.List.of(), Set.of(), java.util.Map.of()))).isEmpty();
        assertThat(e.evaluate(withTrajectory(300_001, java.util.Map.of(), java.util.List.of(), Set.of(), java.util.Map.of()))).hasSize(1);
    }

    @Test
    void neverCallsIsViolatedByARefusalOrByTheCallItself() {
        var refused = new SuperviseEvaluator(block("assert never calls shell { cancel }"), Set.of());
        assertThat(refused.evaluate(withTrajectory(0, java.util.Map.of(), java.util.List.of(), Set.of(), java.util.Map.of("shell", 1)))).hasSize(1);
        var called = new SuperviseEvaluator(block("assert never calls shell { cancel }"), Set.of());
        assertThat(called.evaluate(withTrajectory(0, java.util.Map.of("shell", 1), java.util.List.of("shell"), Set.of(), java.util.Map.of()))).hasSize(1);
    }

    @Test
    void aRuleIndexOutsideTheBlockIsIgnoredWhenReadingWhatFiredBefore() {
        var e = new SuperviseEvaluator(block("when tokens > 5 { steer \"a\" }"), Set.of(-1, 7));
        assertThat(fired(e.evaluate(facts(10, 0, 0, 0)))).hasSize(1);
    }
}
