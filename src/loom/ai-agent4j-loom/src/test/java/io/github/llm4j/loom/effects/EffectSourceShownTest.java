package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.security.Capabilities;
import org.junit.jupiter.api.Test;

class EffectSourceShownTest {

    @Test
    void aDeclaredReasonNamesTheToolAndTheWord() {
        Capabilities c =
                EffectsFixture.resolve(
                        "tool Refunds { use: webhook url: \"https://x.example/h\" effect: changes }");
        assertThat(c.effectReason()).contains("effect: changes").contains("Refunds");
    }

    @Test
    void anAssumedReasonPointsAtEffect() {
        Capabilities c = EffectsFixture.resolve("tool S { use: sql }");
        assertThat(c.effectReason()).contains("assumed reads").contains("effect:");
    }
}
