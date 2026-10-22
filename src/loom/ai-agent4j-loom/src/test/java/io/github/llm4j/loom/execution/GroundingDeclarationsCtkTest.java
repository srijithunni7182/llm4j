package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CTK scenario {@code grounding_declarations}: the script loads and passes the checks. */
class GroundingDeclarationsCtkTest {

    private static final Path SCRIPT = Path.of("../ctk/scripts/grounding_declarations.loom");

    @Test
    void everyGroundedAgentLoadsAndPassesTheChecks() throws Exception {
        LoomScript script = new LoomLoader().load(SCRIPT.toString());
        assertThat(script.getAgents().stream().filter(a -> a.getGrounding() != null))
                .extracting(AgentDef::getName)
                .containsExactly("Pplx", "Claude", "Gem", "Gpt");
        assertThat(script.getBudget().getSearches()).isEqualTo(20L);

        HarnessExecutor e =
                new HarnessExecutor(
                        script,
                        new ToolRegistry(),
                        m -> {
                            throw new IllegalStateException();
                        });
        e.setEnvLookup(name -> "set-" + name);
        List<ScriptValidator.Problem> errors =
                new ScriptValidator()
                        .validate(script, e.validationContext()).stream()
                                .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                                .toList();
        assertThat(errors).isEmpty();
    }
}
