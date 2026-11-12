package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.ToolHook;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guard G10 for the tool path: a forbidden tool call is refused before the tool runs and counted. */
class ForbiddenCallsTest {

    private final Trajectory trajectory = new Trajectory();
    private final ForbiddenCalls forbidden = new ForbiddenCalls(trajectory);

    @Test
    void aForbiddenToolCallIsRejectedByTheHookWithAMessageTheModelCanRead() {
        forbidden.forbid("shell");

        ToolHook.Decision decision = forbidden.hook().beforeCall("shell", Map.of("cmd", "rm -rf /"));

        assertThat(decision).isNotSameAs(ToolHook.Decision.allow());
        assertThat(trajectory.snapshot().refusedOf("shell")).isEqualTo(1);
    }

    @Test
    void aToolThatIsNotForbiddenPassesThrough() {
        forbidden.forbid("shell");

        ToolHook.Decision decision = forbidden.hook().beforeCall("search", Map.of());

        assertThat(decision).isSameAs(ToolHook.Decision.allow());
        assertThat(trajectory.snapshot().refusedOf("search")).isZero();
    }

    @Test
    void aForbiddenTaskOrAgentFailsTheStepAndIsCounted() {
        forbidden.forbid("Pay");

        assertThatThrownBy(() -> forbidden.require("Pay"))
                .hasMessageContaining("Pay")
                .hasMessageContaining("refused");
        forbidden.require("Search"); // not forbidden: nothing happens
        assertThat(trajectory.snapshot().refusedOf("Pay")).isEqualTo(1);
    }
}
