package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.runtime.Unavailable;
import org.junit.jupiter.api.Test;

/**
 * Phase 34a, closing the gaps of phase 33 (F1 to F9): the engine tests on the controlled
 * scheduler. Each test is a guard written before the code it guards.
 */
class ChildClosureTest {

    private final ChildHarness h = new ChildHarness();

    private static final String CHILDREN =
            """
            workflow Good(topic) { run Fine() -> r  return r }
            workflow Bad(topic) { run Explode() -> r  return r }
            """;

    private HarnessExecutor run(String main) {
        HarnessExecutor e = h.parent(CHILDREN + main, new ControlledChildScheduler());
        e.executeWorkflow("Main", java.util.Map.of("q", "x"));
        return e;
    }

    // ---- F2, guard G27: an unfinished child's -> r fails closed ----

    @Test
    void aFailedChildLeavesAnUnavailableValueThatNamesItsStatus() {
        HarnessExecutor e =
                run(
                        """
                        workflow Main(q) {
                            start Bad(topic = q) budget { tokens: 10 } -> work
                            wait work timeout 1m -> outcome on_failure { set handled = "yes" }
                        }
                        """);

        assertThat(e.getContext().getVariable("outcome")).isInstanceOf(Unavailable.class);
        assertThat(e.getContext().getVariable("handled")).isEqualTo("yes");
    }

    @Test
    void readingTheResultOfAFailedChildInTextRaisesTheStatusError() {
        assertThatThrownBy(
                        () ->
                                run(
                                        """
                                        workflow Main(q) {
                                            start Bad(topic = q) budget { tokens: 10 } -> work
                                            wait work timeout 1m -> outcome
                                            set seen = "Result: {outcome}"
                                        }
                                        """))
                .hasMessageContaining("failed")
                .hasMessageContaining("Bad");
    }

    @Test
    void aWaitAllOutcomeOfAFailedChildIsStillReadableAsAStatus() {
        HarnessExecutor e =
                run(
                        """
                        workflow Main(q) {
                            start Bad(topic = q) budget { tokens: 10 } -> work["a"]
                            wait all work timeout 1m -> outcomes
                            set seen = outcomes.a.status
                        }
                        """);

        assertThat(e.getContext().getVariable("seen")).isEqualTo("failed");
    }

    @Test
    void aCompletedChildsResultIsAnOrdinaryValue() {
        HarnessExecutor e =
                run(
                        """
                        workflow Main(q) {
                            start Good(topic = q) budget { tokens: 10 } -> work
                            wait work timeout 1m -> outcome
                            set seen = "Result: {outcome}"
                        }
                        """);

        assertThat(e.getContext().getVariable("seen")).isEqualTo("Result: {outcome=ok, value=ok}");
    }

    @Test
    void weaveCheckWarnsWhenAnOutcomeIsReadWithoutCheckingTheChildFinished() {
        var script =
                StartParseTest.parse(
                        CHILDREN
                                + """
                                workflow Main(q) {
                                    start Bad(topic = q) budget { tokens: 10 } -> work
                                    wait work timeout 1m -> outcome
                                    set seen = "Result: {outcome}"
                                }
                                """);
        var warnings =
                new io.github.llm4j.loom.execution.ScriptValidator()
                        .validate(
                                script,
                                new io.github.llm4j.loom.execution.ScriptValidator.Context()
                                        .registeredTools(java.util.Set.of())
                                        .baseDir(java.nio.file.Path.of(".")));

        assertThat(warnings)
                .anyMatch(
                        p ->
                                p.severity() == io.github.llm4j.loom.execution.ScriptValidator.Severity.WARNING
                                        && p.message().contains("outcome")
                                        && p.message().contains("is ok"));
    }
}
