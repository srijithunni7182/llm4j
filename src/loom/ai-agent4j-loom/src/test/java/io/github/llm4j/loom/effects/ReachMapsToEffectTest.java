package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.security.Capabilities;
import io.github.llm4j.loom.security.Capabilities.EffectSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReachMapsToEffectTest {

    @ParameterizedTest
    @CsvSource({
        "none,NONE,false,false",
        "reads,READS,false,false",
        "fetches,READS,true,false",
        "writes,CHANGES,false,false",
        "sends,CHANGES,false,true"
    })
    void reachOnAClassToolKeepsWorkingAndMapsToAnEffect(
            String reach, TaskEffect effect, boolean untrusted, boolean outward) {
        Capabilities c =
                EffectsFixture.resolve("tool T { use: class class: \"a.B\" reach: " + reach + " }");
        assertThat(c.level()).isEqualTo(effect);
        assertThat(c.untrusted()).isEqualTo(untrusted);
        assertThat(c.outward()).isEqualTo(outward);
        assertThat(c.effectSource())
                .as("reach: is a declaration too")
                .isEqualTo(EffectSource.DECLARED);
        assertThat(c.effectReason()).contains("effect:");
    }
}
