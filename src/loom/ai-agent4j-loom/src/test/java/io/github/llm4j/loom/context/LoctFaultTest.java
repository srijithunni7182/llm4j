package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.context.ContextWindows;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R22: a custom strategy that misbehaves cannot break a run; ai-agent4j's manager contains it. */
class LoctFaultTest {

    @TempDir
    Path dir;

    private TestLoom run(String name, io.github.llm4j.context.ContextCompactor bad) {
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", "context { compact: " + name + "  keep_recent: 2  trigger: 30%  target: 10% }"), 12);
        l.executor.setContextWindows(WindowLayers.none().over("t", ContextWindows.empty().with("tiny-model", new ContextWindow(8000, 500))));
        l.executor.registerCompactor(name, bad);
        l.run();
        return l;
    }

    @Test
    void aStrategyThatThrowsLeavesTheRunUntouched() {
        TestLoom l = run("thrower", new Strategies.Throws());
        assertThat(l.requests).hasSize(13);
    }

    @Test
    void aStrategyThatReturnsMoreIsDiscarded() {
        TestLoom l = run("grower", new Strategies.Grows());
        assertThat(l.requests).hasSize(13);
        assertThat(l.biggest()).as("the grown conversation was never sent").isLessThan(20_000);
    }
}
