package io.github.llm4j.loom.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskNotPerformed;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.model.LLMRequest;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A task that reasons with a model: {@code run Classify(...) model "..." -> intent}. */
class TaskModelTest {

    private final TaskHarness h = new TaskHarness();

    /** A task that asks the model to classify {@code text} and returns its answer as the value. */
    private static Task classify() {
        return Task.withModel(
                "Classify",
                ctx -> {
                    String reply =
                            ctx.llm()
                                    .chat(
                                            LLMRequest.builder()
                                                    .addSystemMessage(
                                                            "Reply with one word: the intent.")
                                                    .addUserMessage(
                                                            ctx.requireArg("text", String.class))
                                                    .build())
                                    .getContent();
                    return TaskResult.value(reply.trim());
                });
    }

    private static HarnessExecutor ran(TaskHarness h, String source) {
        return h.run(source);
    }

    @Test
    void parsesModelClauseInAnyOrder() {
        RunStmt r =
                TaskParseTest.run(
                        "run T(a = 1) -> out retry 2 model \"gemini-2.5-flash\" timeout 5s");
        assertThat(r.getModel()).isEqualTo("gemini-2.5-flash");
        assertThat(r.getRetryCount()).isEqualTo(2);
        assertThat(r.getTimeoutMillis()).isEqualTo(5_000);
        assertThat(TaskParseTest.run("run T() -> out model: \"ollama/llama3\"").getModel())
                .isEqualTo("ollama/llama3");
        assertThat(TaskParseTest.run("run T() -> out").getModel()).isNull();
        assertThatThrownBy(() -> TaskParseTest.run("run T() -> out model \"a\" model \"b\""))
                .hasMessageContaining("given twice");
        assertThatThrownBy(() -> TaskParseTest.run("run T() -> out model"))
                .hasMessageContaining("quoted model name");
    }

    @Test
    void theTaskAsksTheModelNamedInTheScript() {
        h.tasks.register(classify());
        h.answers("refund");
        HarnessExecutor e =
                ran(
                        h,
                        """
                workflow Main() {
                    run Classify(text = "I want my money back") model "test/model" -> intent
                }
                """);
        assertThat(h.modelCalls.get()).isEqualTo(1);
        assertThat(h.modelPrompts).containsExactly("I want my money back");
        assertThat(TaskHarness.map(e.getContext().getVariable("intent")).get("value"))
                .isEqualTo("refund");
        assertThat(h.traceOf(TraceEvent.TASK_START))
                .anySatisfy(t -> assertThat(String.valueOf(t.data())).contains("test/model"));
    }

    @Test
    void aResumedRunDoesNotAskTheModelAgain() {
        h.tasks.register(classify());
        h.answers("refund");
        String script =
                "workflow Main() { run Classify(text = \"hi\") model \"test/model\" -> intent }";
        ran(h, script);
        HarnessExecutor again = ran(h, script); // same journal: the step already happened
        assertThat(h.modelCalls.get()).as("the recorded result is reused").isEqualTo(1);
        assertThat(TaskHarness.map(again.getContext().getVariable("intent")).get("value"))
                .isEqualTo("refund");
    }

    @Test
    void itsSpendIsCountedUnderTheTask() {
        h.tasks.register(classify());
        h.answers("refund");
        HarnessExecutor e =
                ran(
                        h,
                        """
                budget { tokens: 1000 }
                workflow Main() { run Classify(text = "hi") model "test/model" -> intent }
                """);
        assertThat(e.spend().total().calls()).isEqualTo(1);
        assertThat(e.spend().byAgent()).containsOnlyKeys("task Classify");
    }

    @Test
    void aTaskWithoutAModelCannotReachOne() {
        TaskContext plain = TaskContext.of(Map.of(), Map.of());
        assertThatThrownBy(plain::llm)
                .isInstanceOf(TaskNotPerformed.class)
                .hasMessageContaining("model \"...\"");
        assertThat(TaskContext.of(Map.of(), Map.of(), "", "", h.client("m")).llm()).isNotNull();
    }

    @Test
    void validationKeepsTheScriptHonestAboutWhereModelsRun() {
        TaskRegistry tasks =
                new TaskRegistry()
                        .register(classify())
                        .register(Task.pure("Plain", c -> TaskResult.ok()));
        var needsModel = errors("workflow Main() { run Classify(text = \"x\") -> r }", tasks);
        assertThat(needsModel)
                .anyMatch(m -> m.contains("reasons with a model") && m.contains("model \""));
        var notUsed = errors("workflow Main() { run Plain() model \"test/model\" -> r }", tasks);
        assertThat(notUsed).anyMatch(m -> m.contains("never calls a model"));
        assertThat(
                        errors(
                                "workflow Main() { run Classify(text = \"x\") model \"test/model\" -> r  run Plain() -> p }",
                                tasks))
                .isEmpty();
    }

    private static java.util.List<String> errors(String source, TaskRegistry tasks) {
        return new ScriptValidator()
                        .validate(
                                TaskParseTest.parse(source),
                                new ScriptValidator.Context()
                                        .registeredTools(Set.of())
                                        .tasks(tasks))
                        .stream()
                        .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                        .map(ScriptValidator.Problem::toString)
                        .toList();
    }
}
