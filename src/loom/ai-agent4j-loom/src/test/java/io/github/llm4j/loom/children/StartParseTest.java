package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.CancelStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.OutcomeStmt;
import io.github.llm4j.loom.ast.StartStmt;
import io.github.llm4j.loom.ast.SteerStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.SuperviseDef;
import io.github.llm4j.loom.ast.SuperviseStmt;
import io.github.llm4j.loom.ast.WaitStmt;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 33a: {@code start}, {@code wait}, {@code cancel}, {@code steer} and {@code supervise}. */
class StartParseTest {

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    static List<Statement> body(String statements) {
        return parse("workflow Main(q) {\n" + statements + "\n}").getWorkflows().get(0)
                .getStatements();
    }

    @Test
    void startBindsAHandleWithItsDeadlineAndBudget() {
        StartStmt start =
                (StartStmt)
                        body("start Research(topic = q, depth = 2) timeout 20m budget { tokens: 100000 } -> work")
                                .get(0);

        assertThat(start.getWorkflow()).isEqualTo("Research");
        assertThat(start.getArgs()).extracting(a -> a.name()).containsExactly("topic", "depth");
        assertThat(start.getTimeoutMillis()).isEqualTo(20 * 60_000L);
        assertThat(start.getBudget().getTokens()).isEqualTo(100_000L);
        assertThat(start.getTarget().name()).isEqualTo("work");
        assertThat(start.getTarget().isKeyed()).isFalse();
        assertThat(start.getLine()).isEqualTo(2);
    }

    @Test
    void aHandleCanLiveInAKeyedVariable() {
        StartStmt byPath =
                (StartStmt) body("start Research(topic = q) budget { calls: 5 } -> work[t.name]").get(0);
        StartStmt byWord =
                (StartStmt) body("start Research(topic = q) budget { calls: 5 } -> work[\"a\"]").get(0);

        assertThat(byPath.getTarget().isKeyed()).isTrue();
        assertThat(byPath.getTarget().key()).isEqualTo("t.name");
        assertThat(byPath.getTarget().keyIsLiteral()).isFalse();
        assertThat(byWord.getTarget().key()).isEqualTo("a");
        assertThat(byWord.getTarget().keyIsLiteral()).isTrue();
    }

    @Test
    void aStartWithoutABudgetParsesSoTheCheckCanSayWhatToDo() {
        StartStmt start = (StartStmt) body("start Research(topic = q) -> work").get(0);
        assertThat(start.getBudget()).isNull();
    }

    @Test
    void waitForOneChildOrAll() {
        List<Statement> statements =
                body(
                        """
                        wait work timeout 5m -> result
                        wait all work timeout 25m -> results on_timeout { note "late" } on_failure { note "bad" }
                        """);

        WaitStmt one = (WaitStmt) statements.get(0);
        assertThat(one.isAll()).isFalse();
        assertThat(one.getTarget().name()).isEqualTo("work");
        assertThat(one.getTimeoutMillis()).isEqualTo(5 * 60_000L);
        assertThat(one.getResultVariable()).isEqualTo("result");

        WaitStmt all = (WaitStmt) statements.get(1);
        assertThat(all.isAll()).isTrue();
        assertThat(all.getResultVariable()).isEqualTo("results");
        assertThat(all.getOnTimeout()).hasSize(1);
        assertThat(all.getOnFailure()).hasSize(1);
    }

    @Test
    void aWaitWithNoTimeoutParsesSoTheCheckCanRefuseIt() {
        WaitStmt wait = (WaitStmt) body("wait work -> result").get(0);
        assertThat(wait.getTimeoutMillis()).isNull();
    }

    @Test
    void cancelTakesAnOutcomeAndAReason() {
        List<Statement> statements =
                body(
                        """
                        cancel work["b"] use partial reason "not needed"
                        cancel work
                        cancel work rewind to collected at most 2 times
                        """);

        CancelStmt partial = (CancelStmt) statements.get(0);
        assertThat(partial.getTarget().key()).isEqualTo("b");
        assertThat(partial.getOutcome()).isEqualTo(OutcomeStmt.Kind.USE_PARTIAL);
        assertThat(partial.getReason()).isEqualTo("not needed");

        CancelStmt plain = (CancelStmt) statements.get(1);
        assertThat(plain.getOutcome()).isNull();
        assertThat(plain.getReason()).isNull();

        CancelStmt rewind = (CancelStmt) statements.get(2);
        assertThat(rewind.getRewindCheckpoint()).isEqualTo("collected");
        assertThat(rewind.getRewindAtMost()).isEqualTo(2);
    }

    @Test
    void aSteerStatementNamesTheAgentAndCarriesLiteralText() {
        SteerStmt steer =
                (SteerStmt) body("steer work[\"a\"] Researcher \"Narrow the topic.\"").get(0);

        assertThat(steer.getTarget().key()).isEqualTo("a");
        assertThat(steer.getAgent()).isEqualTo("Researcher");
        assertThat(steer.getText()).isEqualTo("Narrow the topic.");
    }

    @Test
    void aSteerTextCannotCarryAPlaceholder() {
        assertThatThrownBy(() -> body("steer work Researcher \"Look at {plan}\""))
                .hasMessageContaining("steer")
                .hasMessageContaining("literal");
    }

    @Test
    void superviseTakesCountedRules() {
        SuperviseStmt supervise =
                (SuperviseStmt)
                        body(
                                        """
                                        supervise work[t.name] budget { tokens: 20000 } {
                                            when tokens > 90000 { cancel use partial }
                                            when idle >= 3m { steer "Narrow the topic." once }
                                            when cost > "$0.50" { wrap_up }
                                            when calls == 40 { cancel }
                                            when elapsed < 10s { steer "Hurry." }
                                        }
                                        """)
                                .get(0);

        assertThat(supervise.getTarget().key()).isEqualTo("t.name");
        assertThat(supervise.getBudget().getTokens()).isEqualTo(20_000L);
        assertThat(supervise.getCounted()).hasSize(5);
        SuperviseStmt.Counted first = supervise.getCounted().get(0);
        assertThat(first.counter()).isEqualTo(SuperviseStmt.Counter.TOKENS);
        assertThat(first.comparison()).isEqualTo(SuperviseStmt.Comparison.GREATER);
        assertThat(first.number()).isEqualTo(90_000L);
        assertThat(first.actions().get(0).kind()).isEqualTo(SuperviseDef.Do.CANCEL);
        assertThat(first.actions().get(0).outcome()).isEqualTo(OutcomeStmt.Kind.USE_PARTIAL);
        SuperviseStmt.Counted idle = supervise.getCounted().get(1);
        assertThat(idle.counter()).isEqualTo(SuperviseStmt.Counter.IDLE);
        assertThat(idle.comparison()).isEqualTo(SuperviseStmt.Comparison.GREATER_OR_EQUAL);
        assertThat(idle.number()).isEqualTo(3 * 60_000L);
        assertThat(idle.once()).isTrue();
        assertThat(supervise.getCounted().get(2).money()).hasToString("0.50");
        assertThat(supervise.getCounted().get(3).comparison())
                .isEqualTo(SuperviseStmt.Comparison.EQUAL);
        assertThat(supervise.getCounted().get(4).comparison())
                .isEqualTo(SuperviseStmt.Comparison.LESS);
    }

    @Test
    void superviseTakesTheFourAssertionsInCode() {
        SuperviseStmt supervise =
                (SuperviseStmt)
                        body(
                                        """
                                        supervise work {
                                            assert never calls shell { cancel }
                                            assert calls search at most 5 times { wrap_up }
                                            assert calls plan before write { steer "Plan first." }
                                            assert reaches "collected" within 10m { steer "Narrow." once }
                                        }
                                        """)
                                .get(0);

        List<SuperviseStmt.CodeAssertion> code = supervise.getCode();
        assertThat(code).extracting(SuperviseStmt.CodeAssertion::kind)
                .containsExactly(
                        SuperviseStmt.AssertionKind.NEVER_CALLS,
                        SuperviseStmt.AssertionKind.CALLS_AT_MOST,
                        SuperviseStmt.AssertionKind.CALLS_BEFORE,
                        SuperviseStmt.AssertionKind.REACHES_WITHIN);
        assertThat(code.get(0).name()).isEqualTo("shell");
        assertThat(code.get(1).limit()).isEqualTo(5);
        assertThat(code.get(2).other()).isEqualTo("write");
        assertThat(code.get(3).name()).isEqualTo("collected");
        assertThat(code.get(3).withinMillis()).isEqualTo(600_000L);
    }

    @Test
    void aJudgedAssertionNamesItsJudgeCadenceAndCeiling() {
        SuperviseStmt supervise =
                (SuperviseStmt)
                        body(
                                        """
                                        supervise work budget { tokens: 20000 } {
                                            assert "stays on topic" using TopicJudge every 2m max 4 { steer "Go back." once }
                                            assert "reads sources" using TopicJudge seeing content every 3 steps max 2 { wrap_up }
                                        }
                                        """)
                                .get(0);

        SuperviseStmt.Judged duration = supervise.getJudged().get(0);
        assertThat(duration.text()).isEqualTo("stays on topic");
        assertThat(duration.evaluator()).isEqualTo("TopicJudge");
        assertThat(duration.everyMillis()).isEqualTo(120_000L);
        assertThat(duration.everySteps()).isNull();
        assertThat(duration.max()).isEqualTo(4);
        assertThat(duration.seeingContent()).isFalse();
        SuperviseStmt.Judged steps = supervise.getJudged().get(1);
        assertThat(steps.everySteps()).isEqualTo(3);
        assertThat(steps.everyMillis()).isNull();
        assertThat(steps.seeingContent()).isTrue();
    }

    @Test
    void aJudgedAssertionMayOnlySteerWrapUpOrCancelUsePartial() {
        assertThatThrownBy(
                        () ->
                                body(
                                        "supervise work budget { tokens: 10 } { assert \"x\" using J every 1m max 1 { cancel discard } }"))
                .hasMessageContaining("steer, wrap_up or cancel use partial");
        assertThatThrownBy(
                        () ->
                                body(
                                        "supervise work budget { tokens: 10 } { assert \"x\" using J every 1m max 1 { cancel } }"))
                .hasMessageContaining("steer, wrap_up or cancel use partial");
    }

    @Test
    void aJudgedAssertionNeedsAMax() {
        assertThatThrownBy(
                        () ->
                                body(
                                        "supervise work budget { tokens: 10 } { assert \"x\" using J every 1m { wrap_up } }"))
                .hasMessageContaining("max");
    }

    @Test
    void aRuleThatDoesNothingIsRefused() {
        assertThatThrownBy(() -> body("supervise work { when tokens > 5 { } }"))
                .hasMessageContaining("does nothing");
    }

    @Test
    void anUnknownCounterIsRefusedWithTheList() {
        assertThatThrownBy(() -> body("supervise work { when status > 5 { cancel } }"))
                .hasMessageContaining("tokens, cost, calls, elapsed or idle");
    }

    @Test
    void theTopLevelSettingsAreRead() {
        LoomScript script =
                parse("max_children: 5\nmax_depth: 3\nworkflow Main(q) { note \"x\" }");
        assertThat(script.getMaxChildren()).isEqualTo(5);
        assertThat(script.getMaxDepth()).isEqualTo(3);
    }

    @Test
    void theNewWordsStayOrdinaryWordsEverywhereElse() {
        LoomScript script =
                parse(
                        """
                        agent Writer { model: "m" }
                        workflow Main(start) {
                            delegate "go" to Writer -> wait
                            set cancel = "x"
                            note "steer {wait}"
                        }
                        """);
        assertThat(script.getWorkflows().get(0).getStatements()).hasSize(3);
    }
}
