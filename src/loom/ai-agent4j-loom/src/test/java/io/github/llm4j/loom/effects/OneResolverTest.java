package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.security.Capabilities;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OneResolverTest {

    @Test
    void thePathTheAuditTheTraceAndReviewShareGivesTheAnswerTheRuntimeSees() {
        LoomScript s =
                EffectsFixture.parse(
                        "tool Orders { use: http base_url: \"https://a.example\" methods: \"POST\" effect: reads }");
        Map<String, ToolDef> tools = EffectsFixture.tools(s);
        Capabilities viaResolve = Capabilities.resolve("Orders", tools, Map.of());
        io.github.llm4j.loom.security.ToolReach reach =
                io.github.llm4j.loom.security.ToolReach.resolve("Orders", tools, Map.of());
        assertThat(viaResolve.effect()).isFalse();
        assertThat(reach.outward()).isEqualTo(viaResolve.outward());
    }
}
