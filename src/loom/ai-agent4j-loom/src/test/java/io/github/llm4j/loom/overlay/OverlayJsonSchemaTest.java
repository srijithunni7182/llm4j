package io.github.llm4j.loom.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.graph.GraphJson;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R37: the overlay JSON follows the graph schema, so the editor and Fabric can rely on it. */
class OverlayJsonSchemaTest {

    @TempDir Path dir;

    @Test
    void theGraphWithAnOverlayValidatesAgainstTheSchema() throws Exception {
        var b = OverlayFixtures.build(dir, "triage order status");
        var o = RunOverlay.build(b.graph(), List.of(b.run()));
        JsonNode json =
                new ObjectMapper()
                        .readTree(
                                GraphJson.write(b.graph(), Map.of("overlay", OverlayJson.tree(o))));
        assertThat(io.github.llm4j.loom.graph.GraphJsonTest.schema().validate(json)).isEmpty();
        JsonNode overlay = json.get("overlay");
        assertThat(overlay.get("version").asInt()).isEqualTo(1);
        assertThat(overlay.get("runs").get(0).asText()).isEqualTo("triage order status");
        assertThat(overlay.get("decisions")).isNotEmpty();
    }

    @Test
    void aGraphWithoutRunsHasNoOverlayKey() throws Exception {
        var b = OverlayFixtures.build(dir, "straight");
        JsonNode json = new ObjectMapper().readTree(GraphJson.write(b.graph()));
        assertThat(json.has("overlay")).isFalse();
    }
}
