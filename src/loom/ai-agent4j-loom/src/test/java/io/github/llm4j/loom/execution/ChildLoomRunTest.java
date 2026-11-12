package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Phase 33, D18: a host sees a run's children as runs, and a console reaches one by a longer label. */
class ChildLoomRunTest {

    private static final String SCRIPT =
            """
            agent Looper { model: "test" system: "You are Looper." tools: [MockTool] max_iterations: 50 steerable: true streaming: true }
            workflow Child(x) { delegate "write" to Looper -> out_text  return out_text }
            workflow Main(q) {
                start Child(x = q) budget { tokens: 1000000 } -> work["a"]
                wait all work timeout 10s -> results
            }
            """;

    @Test
    void aRunListsItsChildrenAsRunsThatRegisterUnderALongerLabelAndCanBeSteered() throws Exception {
        var model = new InterventionRuntimeTest.Model(false);
        Map<String, RunJournal> journals = new ConcurrentHashMap<>();
        ToolRegistry tools = new ToolRegistry();
        tools.register("MockTool", new MockTool());
        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        HarnessExecutor ex = new HarnessExecutor(script, tools, model.factory());
        ex.setRunId("p");
        ex.setJournal(journals.computeIfAbsent("p", k -> RunJournal.inMemory()));
        ex.setChildRuns(id -> journals.computeIfAbsent(id, k -> RunJournal.inMemory()));
        ex.setChildScheduler(ChildScheduler.threads());
        ex.initialize();

        LoomRun run = LoomRun.start(ex, "digest", "Main", Map.of("q", "x"), "app");
        assertThat(model.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        List<LoomRun> children = run.children();
        assertThat(children).hasSize(1);
        LoomRun child = children.get(0);
        assertThat(child.status()).isEqualTo(LoomRun.Status.RUNNING);
        assertThat(LiveRuns.shared().all()).extracting(LiveRuns.Entry::label).contains("digest", "digest/work/a/Child");
        assertThat(child.steer("Looper", "focus on the EU")).as("a child is reached like any run").isEqualTo(1);

        model.finishNow = true;
        model.release.countDown();
        assertThat(run.await(Duration.ofSeconds(10))).isTrue();
        assertThat(run.status()).isEqualTo(LoomRun.Status.COMPLETED);
        assertThat(child.status()).isEqualTo(LoomRun.Status.COMPLETED);
        assertThat(child.await(Duration.ofSeconds(5))).isTrue();
        assertThat(LiveRuns.shared().all()).extracting(LiveRuns.Entry::label).doesNotContain("digest/work/a/Child");
    }
}
