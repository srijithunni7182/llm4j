package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Phase 33d: {@code steer} reaches a steerable agent of a child, or is recorded as not delivered (D12). */
class ChildSteerTest {

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static final String SOURCE =
            """
            agent Looper { model: "test" system: "You are Looper." tools: [MockTool] max_iterations: 50 steerable: true streaming: true }
            workflow Child(x) { delegate "write" to Looper -> out_text  return out_text }
            workflow Main(q) {
                start Child(x = q) budget { tokens: 1000000 } -> work
                run Pump() -> pumped
                steer work Looper "focus on the EU"
                run Release() -> released
                wait work timeout 10s -> out
            }
            """;

    @Tag("slow") // real threads: the child's agent is held inside a model call while the parent steers it
    @Test
    void aSteerReachesTheRunningSteerableAgentAndIsJournaledOnce() throws Exception {
        var model = new InterventionRuntimeTest.Model(false);
        Map<String, RunJournal> journals = new ConcurrentHashMap<>();
        TaskRegistry tasks = new TaskRegistry();
        tasks.register(
                Task.of(
                        "Pump",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            try {
                                assertThat(model.entered.await(10, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return TaskResult.value("agent is running");
                        }));
        tasks.register(
                Task.of(
                        "Release",
                        TaskEffect.NONE,
                        EffectPolicy.DEFAULT,
                        ctx -> {
                            model.finishNow = true;
                            model.release.countDown();
                            return TaskResult.value("released");
                        }));
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        HarnessExecutor parent = new HarnessExecutor(parse(SOURCE), tools, model.factory());
        parent.setTaskRegistry(tasks);
        parent.setRunId("p");
        parent.setJournal(journals.computeIfAbsent("p", k -> RunJournal.inMemory()));
        parent.setChildRuns(id -> journals.computeIfAbsent(id, k -> RunJournal.inMemory()));
        parent.setChildScheduler(ChildScheduler.threads());
        parent.initialize();

        parent.executeWorkflow("Main", Map.of("q", "x"));

        RunJournal.Entry steer =
                journals.get("p").all().values().stream()
                        .filter(e -> "child-steer".equals(e.kind()))
                        .findFirst()
                        .orElseThrow();
        assertThat(steer.value()).asString().contains("delivered=1").contains("focus on the EU");
        assertThat(model.briefings).as("the agent was told").anyMatch(b -> b.contains("focus on the EU"));
    }

    @Test
    void aSteerWithNoRunningAgentIsRecordedAsNotDeliveredAndIsNotAnError() {
        var quickModel = new InterventionRuntimeTest.Model(false);
        quickModel.blockFirstCall = false; // the child runs to its end when the parent waits
        quickModel.finishNow = true;
        Map<String, RunJournal> journals = new ConcurrentHashMap<>();
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        HarnessExecutor parent =
                new HarnessExecutor(
                        parse(
                                """
                                agent Looper { model: "test" system: "You are Looper." tools: [MockTool] steerable: true streaming: true }
                                workflow Child(x) { delegate "write" to Looper -> out_text  return out_text }
                                workflow Main(q) {
                                    start Child(x = q) budget { tokens: 1000000 } -> work
                                    steer work Looper "focus on the EU"
                                    wait work timeout 10s
                                }
                                """),
                        tools,
                        quickModel.factory());
        parent.setRunId("p");
        parent.setJournal(journals.computeIfAbsent("p", k -> RunJournal.inMemory()));
        parent.setChildRuns(id -> journals.computeIfAbsent(id, k -> RunJournal.inMemory()));
        parent.setChildScheduler(new ControlledChildScheduler()); // the child has not run: no agent is running
        parent.initialize();

        parent.executeWorkflow("Main", Map.of("q", "x"));

        RunJournal.Entry steer =
                journals.get("p").all().values().stream()
                        .filter(e -> "child-steer".equals(e.kind()))
                        .findFirst()
                        .orElseThrow();
        assertThat(steer.value()).asString().contains("delivered=0");
    }
}
