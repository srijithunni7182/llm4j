package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.security.Capabilities;
import io.github.llm4j.loom.security.Capabilities.EffectSource;
import org.junit.jupiter.api.Test;

class DeclaredEffectWinsTest {

    @Test
    void aPostToolDeclaredReadsDoesNotChangeAnything() {
        Capabilities c =
                EffectsFixture.resolve(
                        "tool Orders { use: http base_url: \"https://api.acme.com\" methods: \"GET,POST\" effect: reads }");
        assertThat(c.level()).isEqualTo(TaskEffect.READS);
        assertThat(c.effect()).isFalse();
        assertThat(c.effectSource()).isEqualTo(EffectSource.DECLARED);
        assertThat(c.outward()).as("the trifecta leg is a different question").isTrue();
    }

    @Test
    void aGetToolDeclaredChangesChanges() {
        Capabilities c =
                EffectsFixture.resolve(
                        "tool Odd { use: http base_url: \"https://api.acme.com\" methods: \"GET\" effect: changes }");
        assertThat(c.level()).isEqualTo(TaskEffect.CHANGES);
        assertThat(c.effect()).isTrue();
        assertThat(c.effectSource()).isEqualTo(EffectSource.DECLARED);
    }

    @Test
    void aClassToolDeclaredReadsStopsBeingAssumedToChange() {
        Capabilities c =
                EffectsFixture.resolve(
                        "tool Mine { use: class class: \"com.acme.Mine\" effect: reads }");
        assertThat(c.effect()).isFalse();
        assertThat(c.effectSource()).isEqualTo(EffectSource.DECLARED);
    }
}
