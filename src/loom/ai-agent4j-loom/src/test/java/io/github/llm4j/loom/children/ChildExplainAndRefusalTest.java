package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.explain.ScriptExplainer;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase 33: the plain sentences of {@code weave explain}, and a start with no ChildRuns failing closed (D18). */
class ChildExplainAndRefusalTest {

    private static final String SOURCE =
            """
            agent Researcher { model: "m" system: "s" steerable: true streaming: true }
            evaluator TopicJudge { model: "m" }
            workflow Research(topic) { delegate "Find {topic}" to Researcher -> notes  checkpoint collected  return notes }
            workflow Main(q) {
                start Research(topic = q) timeout 20m budget { tokens: 100000 } -> work
                supervise work budget { tokens: 20000 } {
                    when tokens > 90000 { cancel use partial }
                    assert never calls shell { cancel }
                    assert "stays on topic" using TopicJudge every 2m max 4 { steer "Go back." once }
                }
                wait work timeout 25m -> result
                steer work Researcher "Narrow it."
                cancel work use partial reason "not needed"
            }
            """;

    @Test
    void theExplanationSaysWhatEachStatementDoes() {
        String text = ScriptExplainer.explain(StartParseTest.parse(SOURCE), "w.loom", "Main");

        assertThat(text)
                .contains("The workflow Research is started as a run of its own")
                .contains("20 minutes to finish")
                .contains("100000 tokens")
                .contains("the handle is kept as work")
                .contains("when tokens > 90000")
                .contains("the child may never call shell (the call is refused)")
                .contains("a judge (TopicJudge) is asked, at most 4 times, every 2 minutes")
                .contains("it sees facts only")
                .contains("The script waits for work for at most 25 minutes")
                .contains("is told, in these words: \"Narrow it.\"")
                .contains("is cancelled (use partial)")
                .contains("an effect already under way finishes first");
    }

    @Test
    void anExecutorWithNoPlaceToKeepAChildRunRefusesToStartOne() {
        HarnessExecutor executor =
                new HarnessExecutor(
                        StartParseTest.parse(
                                "workflow Main(q) { start Main(q = q) budget { tokens: 1 } -> me }"),
                        new ToolRegistry(),
                        model -> null);
        executor.initialize();

        assertThatThrownBy(() -> executor.executeWorkflow("Main", Map.of("q", "x")))
                .hasMessageContaining("start needs somewhere to keep the child's run")
                .hasMessageContaining("ChildRuns")
                .hasMessageContaining("call");
    }
}
