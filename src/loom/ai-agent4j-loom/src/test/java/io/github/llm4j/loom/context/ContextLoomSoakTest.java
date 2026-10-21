package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.context.ContextWindows;
import io.github.llm4j.exception.ContextOverflowException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R12: a Loom run whose tool loop is far longer than the window completes with compaction on, and
 * fails as it always did with it off.
 */
class ContextLoomSoakTest {

    @TempDir Path dir;

    private static final int STEPS = 150;
    private static final long USABLE = 2500;

    private static WindowLayers tiny() {
        return WindowLayers.none()
                .over(
                        "test",
                        ContextWindows.empty().with("tiny-model", new ContextWindow(3000, 500)));
    }

    @Test
    void withCompactionTheRunCompletesAndNeverExceedsTheWindow() {
        TestLoom l =
                new TestLoom(
                        dir,
                        TestLoom.script(
                                "tiny-model",
                                "context { compact: chain(trim_tool_results, drop_oldest)  keep_recent: 4  max_compactions: 500 }"),
                        STEPS);
        l.refuseAbove = USABLE;
        l.executor.setContextWindows(tiny());
        l.run();
        assertThat(l.requests).hasSize(STEPS + 1);
        assertThat(l.biggest()).isLessThanOrEqualTo(USABLE);
    }

    @Test
    void withoutABlockTheProviderRefusesAsBefore() {
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", ""), STEPS);
        l.refuseAbove = USABLE;
        l.executor.setContextWindows(tiny());
        assertThatThrownBy(l::run).hasRootCauseInstanceOf(ContextOverflowException.class);
    }

    @Test
    void anUnknownWindowStillRecoversFromTheProvidersRefusal() {
        TestLoom l =
                new TestLoom(
                        dir,
                        TestLoom.script(
                                "mystery-model",
                                "context { compact: drop_oldest  keep_recent: 4 }"),
                        40);
        l.refuseAbove = USABLE;
        l.executor.setContextWindows(WindowLayers.none());
        l.run();
        assertThat(l.requests.size()).isGreaterThanOrEqualTo(41);
    }
}
