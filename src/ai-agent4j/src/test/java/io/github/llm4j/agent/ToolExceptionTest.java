package io.github.llm4j.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.telemetry.ErrorCategory;
import org.junit.jupiter.api.Test;

class ToolExceptionTest {

    @Test
    void carriesItsCategoryAndMessage() {
        ToolException e = new ToolException(ErrorCategory.TIMEOUT, "no answer in 10 s");
        assertThat(e.category()).isEqualTo(ErrorCategory.TIMEOUT);
        assertThat(e.getMessage()).isEqualTo("no answer in 10 s");
        assertThat(e.getCause()).isNull();
    }

    @Test
    void canWrapTheCause() {
        Exception cause = new java.io.IOException("reset");
        ToolException e = new ToolException(ErrorCategory.NETWORK, "backend unreachable", cause);
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void needsACategory() {
        assertThatThrownBy(() -> new ToolException(null, "x"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void canBeThrownFromAToolBecauseExecuteThrowsException() {
        Tool t =
                new Tool() {
                    @Override
                    public String getName() {
                        return "t";
                    }

                    @Override
                    public String getDescription() {
                        return "d";
                    }

                    @Override
                    public String execute(java.util.Map<String, Object> args) {
                        throw new ToolException(ErrorCategory.NOT_FOUND, "no such thing");
                    }
                };
        assertThatThrownBy(() -> t.execute(java.util.Map.of())).isInstanceOf(ToolException.class);
    }
}
