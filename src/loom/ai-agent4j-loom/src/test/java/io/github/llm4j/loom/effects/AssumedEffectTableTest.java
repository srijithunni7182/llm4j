package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.security.Capabilities;
import io.github.llm4j.loom.security.Capabilities.EffectSource;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AssumedEffectTableTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "use: calculator|NONE",
                "use: datetime|NONE",
                "use: translate|NONE",
                "use: duckduckgo|READS",
                "use: sql|READS",
                "use: knowledge_graph read_only: true|READS",
                "use: knowledge_graph|CHANGES",
                "use: skill_registry url: \"https://x.example/s\"|READS",
                "use: webhook url: \"https://x.example/h\"|CHANGES",
                "use: email|CHANGES",
                "use: shell allow: \"ls\"|CHANGES",
                "use: file mode: read|READS",
                "use: file mode: write|CHANGES",
                "use: file mode: read_write|CHANGES",
                "use: http base_url: \"https://a.example\" methods: \"GET\"|READS",
                "use: http base_url: \"https://a.example\" methods: \"GET,POST\"|CHANGES",
                "use: openapi spec: \"s.yaml\"|CHANGES",
                "use: class class: \"a.B\"|CHANGES",
                "use: nothing_known|CHANGES"
            })
    void anUndeclaredToolGetsTheAssumedEffectAndSaysSo(String body, TaskEffect expected) {
        Capabilities c = EffectsFixture.resolve("tool T { " + body + " }");
        assertThat(c.level()).isEqualTo(expected);
        assertThat(c.effect()).isEqualTo(expected == TaskEffect.CHANGES);
        assertThat(c.effectSource()).isEqualTo(EffectSource.ASSUMED);
        assertThat(c.effectReason()).contains("effect: none | reads | changes");
    }

    @Test
    void aToolTheHostRegisteredIsAssumedToChange() {
        Capabilities c = Capabilities.resolve("Custom", Map.of(), Map.of());
        assertThat(c.level()).isEqualTo(TaskEffect.CHANGES);
        assertThat(c.effectSource()).isEqualTo(EffectSource.ASSUMED);
    }

    @Test
    void aHarmlessBuiltInUsedByNameIsNone() {
        assertThat(Capabilities.resolve("calculator", Map.of(), Map.of()).level())
                .isEqualTo(TaskEffect.NONE);
    }
}
