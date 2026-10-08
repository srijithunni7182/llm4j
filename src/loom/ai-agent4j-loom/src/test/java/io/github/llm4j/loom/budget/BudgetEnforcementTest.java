package io.github.llm4j.loom.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.budget.BudgetExceeded;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verification plan, Requirements 3.4, 6 and 9.1 (V3.4, V6.1–V6.8, V9.1) and scenarios E2E-1 and E2E-3.
 * Every call is a standard call: 150 tokens (estimate 100 prompt + per_call 50; usage 100 + 50).
 */
class BudgetEnforcementTest {

    /** Agents with a per-call cap of 50, so each call reserves and spends exactly 150 tokens. */
    private static String agents(String... names) {
        StringBuilder s = new StringBuilder();
        for (String n : names) {
            s.append("agent ").append(n).append(" { model: \"test/model\" system: \"You are ").append(n)
                    .append(".\" budget { per_call: 50 } }\n");
        }
        return s.toString();
    }

    @Test
    void v6_1_eachCallIsChargedToRunAgentAndStep() {
        BudgetScript run = new BudgetScript("""
                budget { tokens: 1000 }
                agent Writer { model: "test/model" system: "You are Writer." budget { tokens: 400 per_call: 50 } }
                workflow Main() {
                    delegate "one" to Writer -> a budget 300 tokens
                    delegate "two" to Writer -> b budget 100 tokens on_failure { note "step refused: {_error}" }
                }
                """).run();
        assertThat(run.executor.getRunBudget().spent().tokens()).isEqualTo(150);
        assertThat(run.executor.getAgentBudget("Writer").spent().tokens()).isEqualTo(150);
        // The second step's own budget (100) is part of its call's budget set: it refuses on its own.
        assertThat(run.calls("Writer")).isEqualTo(1);
        assertThat(run.var("b")).isIn(null, "");
    }

    @Test
    void v6_2_aRefusedStepGoesToOnFailureAndIsNeverRetried() {
        BudgetScript run = new BudgetScript(agents("Writer", "Fallback") + """
                workflow Main() {
                    delegate "write" to Writer -> draft budget 100 tokens retry 3 on_failure {
                        delegate "recover from: {_error}" to Fallback -> recovered
                    }
                }
                """).run();
        assertThat(run.calls("Writer")).isZero();
        assertThat(run.calls("Fallback")).isEqualTo(1);
        assertThat(run.tasks).anySatisfy(t -> assertThat(t).contains("recover from: budget exhausted: step").contains("tokens"));
        assertThat(run.var("recovered")).isEqualTo("Fallback#1");
    }

    @Test
    void v6_3a_aPartialAnswerIsKeptAndOnFailureRuns() {
        BudgetScript run = new BudgetScript("""
                agent Researcher { model: "test/model" system: "You are Researcher." tools: [Mock] budget { calls: 1 per_call: 50 } }
                agent Fallback { model: "test/model" system: "You are Fallback." budget { per_call: 50 } }
                workflow Main() {
                    delegate "dig" to Researcher -> notes on_failure { delegate "partial: {notes} / {_budget.exhausted}" to Fallback -> after }
                }
                """).answers("Researcher", "tool:T1", "never reached").run();
        assertThat(run.calls("Researcher")).isEqualTo(1);
        assertThat(run.var("notes")).isEqualTo("T1");
        assertThat(run.tasks).anySatisfy(t -> assertThat(t).contains("partial: T1 / true"));
    }

    @Test
    void v6_3b_withoutOnFailureTheRunContinuesWithThePartialAnswer() {
        BudgetScript run = new BudgetScript("""
                agent Researcher { model: "test/model" system: "You are Researcher." tools: [Mock] budget { calls: 1 per_call: 50 } }
                agent Writer { model: "test/model" system: "You are Writer." budget { per_call: 50 } }
                workflow Main() {
                    delegate "dig" to Researcher -> notes
                    delegate "write from {notes}" to Writer -> draft
                }
                """).answers("Researcher", "tool:T1", "never reached").run();
        assertThat(run.var("notes")).isEqualTo("T1");
        assertThat(run.var("draft")).isEqualTo("Writer#1");
    }

    @Test
    void v6_4a_aLoopBudgetEndsTheLoopAndRunsOnExhausted() {
        BudgetScript run = new BudgetScript(agents("Worker", "Reporter") + """
                workflow Main() {
                    loop until (x == "never") max 10 budget 450 tokens {
                        delegate "round {_loopRound}" to Worker -> x
                    } on_exhausted {
                        delegate "stopped by {_loopExhaustedBy} after {_loopRounds}" to Reporter -> report
                    }
                }
                """).run();
        assertThat(run.calls("Worker")).isEqualTo(3);
        assertThat(run.var("_loopExhaustedBy")).isEqualTo("budget");
        assertThat(run.var("_loopRounds")).isEqualTo("3");
        assertThat(run.tasks).anySatisfy(t -> assertThat(t).contains("stopped by budget after 3"));
    }

    @Test
    void v6_4b_reachingMaxIsExhaustionByRounds() {
        BudgetScript run = new BudgetScript(agents("Worker") + """
                workflow Main() {
                    loop until (x == "never") max 2 {
                        delegate "round" to Worker -> x
                    } on_exhausted { note "done" }
                }
                """).run();
        assertThat(run.var("_loopExhaustedBy")).isEqualTo("rounds");
        assertThat(run.calls("Worker")).isEqualTo(2);
    }

    @Test
    void v6_4c_aForEachBudgetStopsTheItems() {
        BudgetScript handled = new BudgetScript(agents("Worker", "Reporter") + """
                workflow Main() {
                    for each item in items budget 300 tokens {
                        delegate "do {item}" to Worker -> out
                    } on_exhausted {
                        delegate "stopped by {_loopExhaustedBy}" to Reporter -> report
                    }
                }
                """);
        handled.executor.getContext().setVariable("items", List.of("a", "b", "c", "d", "e"));
        handled.run();
        assertThat(handled.calls("Worker")).isEqualTo(2);
        assertThat(handled.tasks).anySatisfy(t -> assertThat(t).contains("stopped by budget"));

        BudgetScript unhandled = new BudgetScript(agents("Worker") + """
                workflow Main() {
                    for each item in items budget 300 tokens { delegate "do {item}" to Worker -> out }
                }
                """);
        unhandled.executor.getContext().setVariable("items", List.of("a", "b", "c", "d", "e"));
        assertThatThrownBy(unhandled::run).isInstanceOf(BudgetExceeded.class);
        assertThat(unhandled.calls("Worker")).isEqualTo(2);
    }

    @Test
    void v6_5_anUnhandledRefusalStopsTheRunAndKeepsWhatWasPaidFor() {
        BudgetScript run = new BudgetScript("budget { tokens: 300 }\n" + agents("Writer") + """
                workflow Main() {
                    delegate "one" to Writer -> a
                    delegate "two" to Writer -> b
                    delegate "three" to Writer -> c
                }
                """);
        assertThatThrownBy(run::run).isInstanceOfSatisfying(BudgetExceeded.class, e -> assertThat(e.budget()).isEqualTo("run"));
        assertThat(run.var("a")).isEqualTo("Writer#1");
        assertThat(run.var("b")).isEqualTo("Writer#2");
        assertThat(run.var("c")).isIn(null, "");
        assertThat(run.executor.spend().total().tokens()).isEqualTo(300);
    }

    @Test
    void v6_6_budgetVariablesRouteAndRender() {
        BudgetScript run = new BudgetScript("budget { tokens: 1000 }\n" + agents("Writer", "Cheap") + """
                workflow Main() {
                    delegate "1" to Writer -> draft1
                    delegate "2" to Writer -> draft2
                    delegate "3" to Writer -> draft3
                    delegate "4" to Writer -> draft4
                    alt (_budget.remaining < 500) {
                        delegate "polish cheaply; spent {_budget.spent}" to Cheap -> final
                    } else {
                        delegate "polish" to Writer -> final
                    }
                }
                """).run();
        assertThat(run.calls("Cheap")).isEqualTo(1);
        assertThat(run.calls("Writer")).isEqualTo(4);
        assertThat(run.tasks).anySatisfy(t -> assertThat(t).contains("polish cheaply; spent 600"));
    }

    @Test
    @Timeout(20)
    void v6_7_parallelBranchesShareTheRunBudgetExactly() {
        String branches = """
                workflow Main() {
                    parallel {
                        delegate "a1" to Writer -> a1
                        delegate "a2" to Writer -> a2
                        delegate "a3" to Writer -> a3
                        delegate "a4" to Writer -> a4
                        delegate "a5" to Writer -> a5
                        delegate "b1" to Writer -> b1
                        delegate "b2" to Writer -> b2
                        delegate "b3" to Writer -> b3
                        delegate "b4" to Writer -> b4
                        delegate "b5" to Writer -> b5
                        delegate "c1" to Writer -> c1
                        delegate "c2" to Writer -> c2
                        delegate "c3" to Writer -> c3
                        delegate "c4" to Writer -> c4
                        delegate "c5" to Writer -> c5
                        delegate "d1" to Writer -> d1
                        delegate "d2" to Writer -> d2
                        delegate "d3" to Writer -> d3
                        delegate "d4" to Writer -> d4
                        delegate "d5" to Writer -> d5
                    }
                }
                """;
        BudgetScript enough = new BudgetScript("budget { tokens: 3000 }\n" + agents("Writer") + branches).run();
        assertThat(enough.calls("Writer")).isEqualTo(20);
        assertThat(enough.executor.getRunBudget().spent().tokens()).isEqualTo(3000);

        BudgetScript half = new BudgetScript("budget { tokens: 1500 }\n" + agents("Writer") + branches);
        assertThatThrownBy(half::run).isInstanceOf(BudgetExceeded.class);
        assertThat(half.calls("Writer")).isEqualTo(10);
        assertThat(half.executor.getRunBudget().spent().tokens()).isEqualTo(1500);
    }

    @Test
    void v6_8_everyRoutingTierIsMetered() {
        BudgetScript run = new BudgetScript("""
                budget { tokens: 1000 }
                routing Tiered { strategy: "COST_AWARE" primary: "test/big" fallback: ["test/small"] }
                agent Writer { routing: Tiered system: "You are Writer." budget { per_call: 50 } }
                workflow Main() { delegate "write" to Writer -> a }
                """).run();
        assertThat(run.calls("Writer")).isEqualTo(1);
        assertThat(run.executor.getRunBudget().spent().tokens()).isEqualTo(150);
        assertThat(run.requests.get(0).getMaxTokens()).isEqualTo(50);
    }

    @Test
    void v3_4_aCostBudgetNeedsAPriceForEveryModelItCovers() {
        String source = "budget { cost: \"$0.01\" }\n" + agents("Writer") + "workflow Main() { delegate \"x\" to Writer -> a }";
        assertThatThrownBy(() -> new BudgetScript(source))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("test/model").hasMessageContaining("--prices");
        BudgetScript priced = new BudgetScript(source, e -> e.setPriceTable(BudgetScript.PRICES)).run();
        assertThat(priced.executor.spend().total().cost()).isEqualByComparingTo("0.0002");
    }

    @Test
    void v9_1_aScriptWithoutBudgetsIsNotMetered() {
        BudgetScript run = new BudgetScript("""
                agent Writer { model: "test/model" system: "You are Writer." }
                workflow Main() { delegate "write" to Writer -> a }
                """).run();
        assertThat(run.executor.getRunBudget()).isNull();
        assertThat(run.var("_budget")).isIn(null, "");
        assertThat(run.requests.get(0).getMaxTokens()).isNull();
        assertThat(run.executor.spend().lines()).isEmpty();
        assertThat(run.var("a")).isEqualTo("Writer#1");
    }

    @Test
    void e2e1_aHobbyRunNeverAsksForMoreThanIsLeft() {
        BudgetScript run = new BudgetScript("""
                budget { tokens: 1000 }
                agent Scout { model: "test/model" system: "You are Scout." budget { per_call: 300 } }
                agent Writer { model: "test/model" system: "You are Writer." budget { per_call: 300 } }
                agent Critic { model: "test/model" system: "You are Critic." budget { per_call: 300 } }
                workflow Main() {
                    loop until (verdict == "never") max 20 {
                        delegate "scout" to Scout -> s
                        delegate "write" to Writer -> w
                        delegate "judge" to Critic -> verdict
                    } on_exhausted { note "stopped" }
                }
                """);
        assertThatThrownBy(run::run).isInstanceOf(BudgetExceeded.class);
        long spent = run.executor.getRunBudget().spent().tokens();
        assertThat(spent).isLessThanOrEqualTo(1000);
        // Every request asked for no more output than was left after its prompt.
        long left = 1000;
        for (var r : run.requests) {
            assertThat(r.getMaxTokens()).isLessThanOrEqualTo((int) (left - 100));
            left -= 150;
        }
    }

    @Test
    void e2e3_theScriptSwitchesToTheCheapAgentAtThePredictedCall() {
        BudgetScript run = new BudgetScript("budget { tokens: 1500 }\n" + agents("Writer", "CheapWriter") + """
                workflow Main() {
                    loop until (_budget.exhausted == "true") max 9 {
                        alt (_budget.remaining < 700) {
                            delegate "cheap draft {_loopRound}" to CheapWriter -> draft
                        } else {
                            delegate "draft {_loopRound}" to Writer -> draft
                        }
                    } on_exhausted { note "done" }
                }
                """).run();
        // 1500 → 1350 → 1200 → 1050 → 900 → 750 (Writer, 6 calls: remaining ≥ 700 before each) → 600 → …
        assertThat(run.calls("Writer")).isEqualTo(6);
        assertThat(run.calls("CheapWriter")).isEqualTo(3);
        var byAgent = run.executor.spend().byAgent();
        assertThat(byAgent.get("Writer").tokens()).isEqualTo(900);
        assertThat(byAgent.get("CheapWriter").tokens()).isEqualTo(450);
    }
}
