package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.McpServerDef;
import io.github.llm4j.loom.security.Capabilities.EffectSource;
import io.github.llm4j.loom.security.McpEffects;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpHintsTest {

    McpServerDef server() {
        McpServerDef s = new McpServerDef("Pg");
        s.putToolEffect("declaredRead", TaskEffect.READS);
        return s;
    }

    static Map<String, Object> listed(Map<String, Object> annotations) {
        return annotations == null
                ? Map.of("name", "t")
                : Map.of("name", "t", "annotations", annotations);
    }

    @Test
    void aDeclaredEffectBeatsAnyAnnotation() {
        var r =
                McpEffects.resolve(
                        server(), "declaredRead", listed(Map.of("destructiveHint", true)));
        assertThat(r.effect()).isEqualTo(TaskEffect.READS);
        assertThat(r.source()).isEqualTo(EffectSource.DECLARED);
    }

    @Test
    void readOnlyHintMeansReadsButStaysAnAssumptionAndSaysWhoseWordItIs() {
        var r = McpEffects.resolve(server(), "t", listed(Map.of("readOnlyHint", true)));
        assertThat(r.effect()).isEqualTo(TaskEffect.READS);
        assertThat(r.source()).isEqualTo(EffectSource.ASSUMED);
        assertThat(r.reason()).contains("read-only").contains("effects:");
    }

    @Test
    void destructiveHintWinsOverReadOnlyHint() {
        var r =
                McpEffects.resolve(
                        server(),
                        "t",
                        listed(Map.of("readOnlyHint", true, "destructiveHint", true)));
        assertThat(r.effect()).isEqualTo(TaskEffect.CHANGES);
    }

    @Test
    void noReadOnlyHintMeansChanges() {
        assertThat(
                        McpEffects.resolve(server(), "t", listed(Map.of("idempotentHint", true)))
                                .effect())
                .isEqualTo(TaskEffect.CHANGES);
    }

    @Test
    void noAnnotationsMeansChanges() {
        var r = McpEffects.resolve(server(), "t", listed(null));
        assertThat(r.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(r.reason()).contains("no annotations");
    }

    @Test
    void aServerThatCouldNotBeAskedMeansChangesAndTheReasonSaysTheHintsWereNotRead() {
        var r = McpEffects.resolve(server(), "t", null);
        assertThat(r.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(r.reason()).contains("could not be read");
    }
}
