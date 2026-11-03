package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.fact.Entity;
import io.github.llm4j.loom.review.fact.FactBase;
import io.github.llm4j.loom.review.fact.FactBuilder;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The review shows each tool's effect, who decided it and why. */
class ToolEffectFactsTest {

    @TempDir Path dir;

    @Test
    void aDeclaredEffectAndAnAssumedOneAreToldApart() {
        String script =
                "tool Orders { use: http base_url: \"https://a.example\" methods: \"POST\" effect: reads }\n"
                        + "tool Pay { use: webhook url: \"https://p.example/h\" }\n"
                        + "agent A { model: \"m\" system: \"s\" tools: [Orders, Pay] }\n"
                        + "workflow Main(q) { delegate \"{q}\" to A -> r }\n";
        FactBase b =
                FactBuilder.standard()
                        .build(
                                ReviewFixtures.input(dir, script, Map.of(), true),
                                ReviewSettings.defaults());
        Entity orders =
                b.entities("tool").stream()
                        .filter(e -> "Orders".equals(e.get("name")))
                        .findFirst()
                        .orElseThrow();
        assertThat(orders.get("effect")).isEqualTo("reads");
        assertThat(orders.get("effectSource")).isEqualTo("declared");
        Entity pay =
                b.entities("tool").stream()
                        .filter(e -> "Pay".equals(e.get("name")))
                        .findFirst()
                        .orElseThrow();
        assertThat(pay.get("effect")).isEqualTo("changes");
        assertThat(pay.get("effectSource")).isEqualTo("assumed");
        assertThat((String) pay.get("effectReason")).contains("effect:");
    }
}
