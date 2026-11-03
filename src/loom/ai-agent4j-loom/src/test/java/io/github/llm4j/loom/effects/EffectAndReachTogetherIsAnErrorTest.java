package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EffectAndReachTogetherIsAnErrorTest {

    @Test
    void bothOnOneToolIsRefusedAndTheMessageSaysToKeepEffect() {
        assertThatThrownBy(
                        () ->
                                EffectsFixture.parse(
                                        "tool T { use: class class: \"a.B\" reach: reads effect: reads }"))
                .hasMessageContaining("Keep effect:")
                .hasMessageContaining("T");
    }
}
