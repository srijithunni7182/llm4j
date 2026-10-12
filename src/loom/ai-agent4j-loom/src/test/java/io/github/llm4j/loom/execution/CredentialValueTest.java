package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** What the executor knows of a credential option when it has to hide one: an invoker credential is known only to a run, which hides it itself. */
class CredentialValueTest {

    private final HarnessExecutor executor = new HarnessExecutor(new LoomParser(new Lexer("agent A { model: \"m\" }").tokenize()).parseScript(),
            new ToolRegistry(), model -> null);

    @Test
    @Tag("CC-V5.1")
    void anInvokerCredentialHasNoValueToHideAtLoadTime() {
        executor.setEnvLookup(Map.of("KEY", "env-value")::get);

        assertThat(executor.credentialValue(ToolDef.OptionValue.invoker("token"))).isNull();
        assertThat(executor.credentialValue(ToolDef.OptionValue.env("KEY"))).isEqualTo("env-value");
        assertThat(executor.credentialValue(ToolDef.OptionValue.literal("plain"))).isEqualTo("plain");
        assertThat(executor.credentialValue(ToolDef.OptionValue.env("UNSET"))).isNull();
    }

    @Test
    @Tag("CC-V5.1")
    void theInvokerOfAThreadThatIsNotRunningAWorkflowIsNone() {
        assertThat(executor.invoker()).isNull();
    }
}
