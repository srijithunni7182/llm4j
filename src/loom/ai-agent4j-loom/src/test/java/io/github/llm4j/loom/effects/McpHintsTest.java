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
    void aReadOnlyAnnotationIsOnlyTheServersWordSoTheEffectIsStillAssumedToChange() {
        var r = McpEffects.resolve(server(), "t", listed(Map.of("readOnlyHint", true)));
        assertThat(r.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(r.source()).isEqualTo(EffectSource.ASSUMED);
        assertThat(r.reason())
                .contains("server's word")
                .contains("weave mcp list --write")
                .contains("effects:");
    }

    @Test
    void aServerThatCouldNotBeAskedMeansChanges() {
        var r = McpEffects.resolve(server(), "t", null);
        assertThat(r.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(r.reason()).contains("no effects: entry");
    }

    @Test
    void proposesReadsOnlyForAToolAnnotatedReadOnly() {
        var p = McpEffects.propose(listed(Map.of("readOnlyHint", true)));
        assertThat(p.effect()).isEqualTo(TaskEffect.READS);
        assertThat(p.onUnknown()).isNull();
        assertThat(p.why()).contains("server");
    }

    @Test
    void aToolAnnotatedBothReadOnlyAndDestructiveGetsTheCautiousProposal() {
        var p = McpEffects.propose(listed(Map.of("readOnlyHint", true, "destructiveHint", true)));
        assertThat(p.effect()).isEqualTo(TaskEffect.CHANGES);
    }

    @Test
    void noAnnotationsProposesChangesAndSkipOnUnknown() {
        var p = McpEffects.propose(listed(null));
        assertThat(p.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(p.onUnknown()).isEqualTo("skip");
        assertThat(p.why()).contains("no annotations");
    }

    @Test
    void anIdempotentHintProposesRetryOnUnknown() {
        var p = McpEffects.propose(listed(Map.of("idempotentHint", true)));
        assertThat(p.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(p.onUnknown()).isEqualTo("retry");
    }

    @Test
    void anUnsetDestructiveHintMeansDestructiveAsTheSpecificationDefaults() {
        var p = McpEffects.propose(listed(Map.of("title", "x")));
        assertThat(p.effect()).isEqualTo(TaskEffect.CHANGES);
    }
}
