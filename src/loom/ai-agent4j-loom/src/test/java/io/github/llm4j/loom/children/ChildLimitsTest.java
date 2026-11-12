package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.execution.ChildLimits;
import io.github.llm4j.loom.execution.ControlledChildScheduler;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Guard G5 of phase 33 (D14): a start past {@code max_children} or {@code max_depth} fails; it does not queue. */
class ChildLimitsTest {

    private static HarnessExecutor executor(String source, ChildLimits limits) {
        Map<String, RunJournal> journals = new ConcurrentHashMap<>();
        TaskRegistry tasks = new TaskRegistry();
        tasks.register(
                Task.of("Fine", TaskEffect.NONE, EffectPolicy.DEFAULT, ctx -> TaskResult.value("ok")));
        HarnessExecutor e =
                new HarnessExecutor(StartParseTest.parse(source), new ToolRegistry(), model -> null);
        e.setTaskRegistry(tasks);
        e.setRunId("p");
        e.setJournal(journals.computeIfAbsent("p", k -> RunJournal.inMemory()));
        e.setChildRuns(id -> journals.computeIfAbsent(id, k -> RunJournal.inMemory()));
        e.setChildScheduler(new ControlledChildScheduler());
        if (limits != null) e.setChildLimits(limits);
        e.initialize();
        return e;
    }

    private static final String LEAF = "workflow Leaf(x) { run Fine() -> r  return r }\n";

    @Test
    void aStartPastMaxChildrenFailsAndNamesTheLimit() {
        HarnessExecutor e =
                executor(
                        "max_children: 2\n"
                                + LEAF
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) budget { tokens: 1 } -> a
                                    start Leaf(x = q) budget { tokens: 1 } -> b
                                    start Leaf(x = q) budget { tokens: 1 } -> c
                                }
                                """,
                        null);

        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of("q", "x")))
                .hasMessageContaining("max_children")
                .hasMessageContaining("2");
    }

    @Test
    void aSettledChildFreesItsSlot() {
        HarnessExecutor e =
                executor(
                        "max_children: 1\n"
                                + LEAF
                                + """
                                workflow Main(q) {
                                    start Leaf(x = q) budget { tokens: 1 } -> a
                                    wait a timeout 1m -> ra
                                    start Leaf(x = q) budget { tokens: 1 } -> b
                                    wait b timeout 1m -> rb
                                }
                                """,
                        null);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(String.valueOf(e.getContext().getVariable("rb"))).contains("ok");
    }

    @Test
    void aScriptCannotRaiseTheLimitPastTheHostsCeiling() {
        HarnessExecutor e =
                executor(
                        "max_children: 50\n"
                                + LEAF
                                + "workflow Main(q) { start Leaf(x = q) budget { tokens: 1 } -> a  wait a timeout 1m }",
                        new ChildLimits(4, 2));

        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of("q", "x")))
                .hasMessageContaining("max_children: 50")
                .hasMessageContaining("ceiling")
                .hasMessageContaining("4");
    }

    @Test
    void aChildThatStartsChildrenIsOneLevelDeeperAndTheDepthLimitHolds() {
        String source =
                "max_depth: 1\n"
                        + LEAF
                        + """
                        workflow Middle(x) {
                            start Leaf(x = x) budget { tokens: 1 } -> inner
                            wait inner timeout 1m -> r
                            return r
                        }
                        workflow Main(q) {
                            start Middle(x = q) budget { tokens: 1 } -> mid
                            wait mid timeout 1m -> out
                            set seen = mid.status
                            set why = mid.failure
                        }
                        """;
        HarnessExecutor e = executor(source, null);

        e.executeWorkflow("Main", Map.of("q", "x"));

        assertThat(e.getContext().getVariable("seen")).isEqualTo("failed");
        assertThat(String.valueOf(e.getContext().getVariable("why"))).contains("max_depth");
    }

    @Test
    void theDefaultsAreEightChildrenAndTwoLevels() {
        assertThat(ChildLimits.DEFAULT_MAX_CHILDREN).isEqualTo(8);
        assertThat(ChildLimits.DEFAULT_MAX_DEPTH).isEqualTo(2);
    }
}
