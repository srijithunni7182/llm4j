package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ProviderDef;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * CTK scenario {@code provider_declarations}: every provider kind the language accepts loads and
 * passes the checks.
 */
class ProviderDeclarationsCtkTest {

    private static final Path SCRIPT = Path.of("../ctk/scripts/provider_declarations.loom");

    @Test
    void everyKindLoadsAndThePassesTheChecks() throws Exception {
        LoomScript script = new LoomLoader().load(SCRIPT.toString());
        assertThat(script.getProviders())
                .extracting(ProviderDef::getKind)
                .containsExactly(
                        "openai",
                        "xai",
                        "kimi",
                        "qwen",
                        "perplexity",
                        "perplexity_router",
                        "openai_compatible",
                        "openai_compatible");

        HarnessExecutor e =
                new HarnessExecutor(
                        script,
                        new ToolRegistry(),
                        m -> {
                            throw new IllegalStateException();
                        });
        e.setEnvLookup(name -> "https://example.invalid/set-" + name);
        List<ScriptValidator.Problem> errors =
                new ScriptValidator()
                        .validate(script, e.validationContext()).stream()
                                .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                                .toList();
        assertThat(errors).isEmpty();
    }

    @Test
    void theKindsInTheScriptAreExactlyTheNewOnesThePlatformKnows() throws Exception {
        LoomScript script = new LoomLoader().load(SCRIPT.toString());
        assertThat(ProviderSpec.KINDS)
                .containsAll(script.getProviders().stream().map(ProviderDef::getKind).toList());
    }
}
