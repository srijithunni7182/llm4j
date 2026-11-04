package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.explain.ScriptExplainer;
import org.junit.jupiter.api.Test;

class ExplainShowsEffectTest {

    @Test
    void explainSaysWhatEachToolDoesAndWhoDecidedIt() {
        String text =
                ScriptExplainer.explain(
                        EffectsFixture.parse(
                                "tool Orders { use: http base_url: \"https://a.example\" methods: \"POST\" effect: reads }\n"
                                        + "tool Pay { use: webhook url: \"https://p.example/h\" }\n"
                                        + "agent A { model: \"m\" system: \"s\" tools: [Orders, Pay] }\n"
                                        + "workflow Main(q) { delegate \"{q}\" to A -> r }\n"),
                        "t.loom",
                        "Main");
        assertThat(text)
                .contains("Tool Orders: reads (declared)")
                .contains("Tool Pay: changes (assumed; say what it does with effect:");
    }
}
