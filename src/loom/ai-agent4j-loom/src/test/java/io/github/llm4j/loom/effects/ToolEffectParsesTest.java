package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.LoomScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ToolEffectParsesTest {

    @ParameterizedTest
    @CsvSource({"none,NONE", "reads,READS", "changes,CHANGES"})
    void effectIsReadOnAnyToolKind(String word, TaskEffect expected) {
        LoomScript s = EffectsFixture.parse("tool T { use: calculator effect: " + word + " }");
        assertThat(s.getTools().get(0).getEffect()).contains(expected);
    }

    @Test
    void noEffectMeansNoDeclaration() {
        LoomScript s = EffectsFixture.parse("tool T { use: calculator }");
        assertThat(s.getTools().get(0).getEffect()).isEmpty();
    }

    @Test
    void anUnknownWordIsRefusedAndTheMessageNamesTheThreeWords() {
        assertThatThrownBy(() -> EffectsFixture.parse("tool T { use: calculator effect: writes }"))
                .hasMessageContaining("none, reads or changes")
                .hasMessageContaining("writes");
    }

    @Test
    void effectGivenTwiceIsRefused() {
        assertThatThrownBy(
                        () ->
                                EffectsFixture.parse(
                                        "tool T { use: calculator effect: none effect: reads }"))
                .hasMessageContaining("given twice");
    }

    @Test
    void effectsAreReadPerOperation() {
        LoomScript s =
                EffectsFixture.parse(
                        "tool S { use: openapi spec: \"s.yaml\" effects: { listOrders: reads, createRefund: changes } }");
        assertThat(s.getTools().get(0).getOperationEffects())
                .containsEntry("listOrders", TaskEffect.READS)
                .containsEntry("createRefund", TaskEffect.CHANGES);
    }

    @Test
    void anOperationListedTwiceIsRefused() {
        assertThatThrownBy(
                        () ->
                                EffectsFixture.parse(
                                        "tool S { use: openapi spec: \"s.yaml\" effects: { a: reads, a: changes } }"))
                .hasMessageContaining("given twice");
    }

    @Test
    void anMcpServerDeclaresTheEffectOfEachToolByName() {
        LoomScript s =
                EffectsFixture.parse(
                        "mcp Pg { transport: \"stdio\" cmd: \"x\" effects: { query: reads, drop: changes } }");
        assertThat(s.getMcpServers().get(0).getToolEffects())
                .containsEntry("query", TaskEffect.READS)
                .containsEntry("drop", TaskEffect.CHANGES);
    }
}
