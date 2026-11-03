package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.security.Capabilities;
import org.junit.jupiter.api.Test;

class OpenApiOperationEffectsTest {

    static final String SHOP =
            "tool Shop { use: openapi spec: \"s.yaml\" effects: { listOrders: reads, getOrder: reads, createRefund: changes } }";

    ToolDef shop() {
        return EffectsFixture.parse(SHOP).getTools().get(0);
    }

    @Test
    void aListedOperationHasItsDeclaredEffect() {
        Capabilities whole = EffectsFixture.resolve(SHOP);
        assertThat(Capabilities.operationEffect(shop(), "listOrders", whole))
                .isEqualTo(TaskEffect.READS);
        assertThat(Capabilities.operationEffect(shop(), "createRefund", whole))
                .isEqualTo(TaskEffect.CHANGES);
    }

    @Test
    void anOperationNotListedFallsBackToTheToolsOwnEffectWhichIsChanges() {
        Capabilities whole = EffectsFixture.resolve(SHOP);
        assertThat(Capabilities.operationEffect(shop(), "deleteEverything", whole))
                .isEqualTo(TaskEffect.CHANGES);
    }

    @Test
    void aDeclaredToolWideEffectIsTheFallbackForUnlistedOperations() {
        String src =
                "tool Shop { use: openapi spec: \"s.yaml\" effect: reads effects: { createRefund: changes } }";
        Capabilities whole = EffectsFixture.resolve(src);
        ToolDef def = EffectsFixture.parse(src).getTools().get(0);
        assertThat(Capabilities.operationEffect(def, "anything", whole))
                .isEqualTo(TaskEffect.READS);
        assertThat(Capabilities.operationEffect(def, "createRefund", whole))
                .isEqualTo(TaskEffect.CHANGES);
    }
}
