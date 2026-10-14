package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.context.ContextWindows;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R6, R7, R8, R9, R12: an agent with a context block is built with its policy, its model's window and its strategy. */
class ContextAgentBuildTest {

    @TempDir
    Path dir;

    private static final int STEPS = 14;

    private static WindowLayers tiny(String model) {
        return WindowLayers.none().over("test", ContextWindows.empty().with(model, new ContextWindow(3000, 500)));
    }

    private TestLoom loom(String model, String block) {
        return new TestLoom(dir, TestLoom.script(model, block), STEPS);
    }

    @Test
    void anAgentWithoutABlockIsBuiltAsBefore() {
        TestLoom l = loom("tiny-model", "");
        l.executor.setContextWindows(tiny("tiny-model"));
        l.run();
        assertThat(l.requests).hasSize(STEPS + 1);
        assertThat(l.biggest()).as("without a block the prompt just grows").isGreaterThan(3000);
    }

    @Test
    void aBlockKeepsThePromptInsideTheWindowOfItsModel() {
        TestLoom l = loom("tiny-model", "context { compact: drop_oldest  keep_recent: 2  trigger: 70%  target: 40% }");
        l.executor.setContextWindows(tiny("tiny-model"));
        l.run();
        assertThat(l.requests).hasSize(STEPS + 1);
        assertThat(l.biggest()).as("usable input is 2500 tokens").isLessThan(2500);
    }

    @Test
    void theWindowComesFromTheFileNotTheScript() {
        TestLoom small = loom("tiny-model", "context { compact: drop_oldest  keep_recent: 2 }");
        small.executor.setContextWindows(tiny("tiny-model"));
        small.run();
        TestLoom large = loom("tiny-model", "context { compact: drop_oldest  keep_recent: 2 }");
        large.executor.setContextWindows(WindowLayers.none().over("t", ContextWindows.empty().with("tiny-model", new ContextWindow(100_000, 500))));
        large.run();
        assertThat(small.biggest()).isLessThan(2500);
        assertThat(large.biggest()).as("a larger window needs no compaction").isGreaterThan(3000);
    }

    @Test
    void aModelWithNoKnownWindowIsNotCompactedByFill() {
        TestLoom l = loom("unknown-model", "context { compact: drop_oldest }");
        l.executor.setContextWindows(WindowLayers.none());
        l.run();
        assertThat(l.requests).hasSize(STEPS + 1);
        assertThat(l.biggest()).isGreaterThan(3000);
    }

    @Test
    void aRoutingAgentIsGovernedByTheSmallestWindowOfItsModels() {
        String src = "routing Pol { primary: \"big-model\"  fallback: [\"tiny-model\"]  strategy: fallback }\n"
                + "agent Researcher {\n  routing: Pol\n  model: \"big-model\"\n  tools: [lookup]\n  max_iterations: 60\n  context { compact: drop_oldest  keep_recent: 2 }\n}\n"
                + "workflow Main() { delegate \"research the topic\" to Researcher -> out }\n";
        TestLoom l = new TestLoom(dir, src, STEPS);
        l.executor.setContextWindows(WindowLayers.none().over("t", ContextWindows.empty().with("big-model", new ContextWindow(1_000_000, 1000)).with("tiny-model", new ContextWindow(3000, 500))));
        l.run();
        assertThat(l.biggest()).isLessThan(2500);
    }

    @Test
    void theSummarizerIsBuiltByTheSameFactoryAsTheAgent() {
        TestLoom l = loom("tiny-model", "context { compact: summarize  summarizer: \"notes-model\"  keep_recent: 2 }");
        l.executor.setContextWindows(tiny("tiny-model"));
        l.run();
        assertThat(l.modelsCreated).contains("tiny-model", "notes-model");
        assertThat(l.biggest()).isLessThan(2500);
    }

    @Test
    void summarizeWithoutASummarizerFallsBackToDroppingTheOldest() {
        TestLoom l = loom("tiny-model", "context { compact: summarize  keep_recent: 2 }");
        l.executor.setContextWindows(tiny("tiny-model"));
        l.run();
        assertThat(l.biggest()).isLessThan(2500);
    }

    @Test
    void aChainIsBuiltInOrder() {
        TestLoom l = loom("tiny-model", "context { compact: chain(trim_tool_results, drop_oldest)  max_chars: 300  keep_recent: 2 }");
        l.executor.setContextWindows(tiny("tiny-model"));
        l.run();
        assertThat(l.biggest()).isLessThan(2500);
    }

    @Test
    void anOptionNoStrategyTakesIsALoadError() {
        TestLoom l = loom("tiny-model", "context { compact: drop_oldest  max_chars: 300 }");
        l.executor.setContextWindows(tiny("tiny-model"));
        assertThatThrownBy(l::run).hasMessageContaining("max_chars");
    }

    @Test
    void aCustomStrategyFromCodeIsUsedByName() {
        Strategies.calls.set(0);
        TestLoom l = loom("tiny-model", "context { compact: chain(keep_newest, drop_oldest)  keep_recent: 2 }");
        l.executor.setContextWindows(tiny("tiny-model"));
        l.executor.registerCompactor("keep_newest", new Strategies.KeepNewest());
        l.run();
        assertThat(Strategies.calls.get()).isPositive();
        assertThat(l.biggest()).isLessThan(2500);
    }

    @Test
    void aCustomStrategyFromALoctFileGetsTheBlocksOptions() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("s.loct"), "with_options = io.github.llm4j.loom.context.Strategies$WithOptions\n");
        TestLoom l = loom("tiny-model", "context { compact: with_options  keep_recent: 2  flavour: spicy }");
        l.executor.setContextWindows(tiny("tiny-model"));
        assertThat(l.executor.loadCompactors(dir.resolve("s.loct"))).isEmpty();
        l.executor.initialize();
        assertThat(Strategies.WithOptions.lastOptions).containsEntry("flavour", "spicy").doesNotContainKey("keep_recent");
    }
}
