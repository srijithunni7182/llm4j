package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.children.StartParseTestAccess;
import io.github.llm4j.loom.runtime.ChildHandle;
import org.junit.jupiter.api.Test;

/**
 * Guard G29 of phase 34 (F7): a handle never reaches text a model could read, even when the static
 * check is bypassed. The static check sees only the handles a workflow binds with {@code start};
 * this is the second line, at the one place placeholders become text.
 */
class ChildTextGuardTest {

    private static HarnessExecutor executorHolding(String name, Object value) {
        HarnessExecutor e =
                new HarnessExecutor(
                        StartParseTestAccess.parse("workflow Main(q) { set x = \"1\" }"),
                        new ToolRegistry(),
                        model -> null);
        e.bind(name, value, Provenance.NOT_MODEL);
        return e;
    }

    @Test
    void aHandleInAPlaceholderFailsClosedInsteadOfBecomingText() {
        HarnessExecutor e = executorHolding("worker", new ChildHandle("p.Main.s0", "Research"));

        assertThatThrownBy(() -> e.resolve("Review this: {worker}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("handle")
                .hasMessageContaining("worker");
    }

    @Test
    void aHandleNamedBareInTextIsOnlyAWordAndIsNeverSubstituted() {
        HarnessExecutor e = executorHolding("worker", new ChildHandle("p.Main.s0", "Research"));

        assertThat(e.resolve("ask the worker about it")).isEqualTo("ask the worker about it");
    }

    @Test
    void aFactOfAHandleIsStillReadable() {
        HarnessExecutor e = executorHolding("worker", new ChildHandle("p.Main.s0", "Research"));

        assertThat(e.resolve("it is {worker.status}")).isEqualTo("it is running");
    }

    @Test
    void anUnavailableChildResultFailsClosedWithTheStatus() {
        HarnessExecutor e =
                executorHolding(
                        "outcome",
                        io.github.llm4j.loom.runtime.Unavailable.of("Research", "failed"));

        assertThatThrownBy(() -> e.resolve("Result: {outcome}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Research")
                .hasMessageContaining("failed");
    }
}
