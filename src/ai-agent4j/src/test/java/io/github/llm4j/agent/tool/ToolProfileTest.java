package io.github.llm4j.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.TaskEffect;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolProfileTest {

    @Test
    void aToolThatChangesSomethingHasTheChangesLevel() {
        ToolProfile p = ToolProfile.of(false, false, true, true, "posts");
        assertThat(p.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(p.outward()).isTrue();
        assertThat(p.known()).isTrue();
        assertThat(p.journaled()).isFalse();
        assertThat(p.isolation()).isEqualTo(Isolation.NONE);
        assertThat(p.note()).isEqualTo("posts");
    }

    @Test
    void aToolThatReadsOutsideOrPrivateDataReadsAndOneThatReachesNothingHasNoEffect() {
        assertThat(ToolProfile.of(true, false, false, false, "n").effect())
                .isEqualTo(TaskEffect.READS);
        assertThat(ToolProfile.of(false, true, false, false, "n").effect())
                .isEqualTo(TaskEffect.READS);
        assertThat(ToolProfile.of(false, false, false, false, "n").effect())
                .isEqualTo(TaskEffect.NONE);
    }

    @Test
    void anUnknownProfileReachesEverythingAndChangesThings() {
        ToolProfile p = ToolProfile.unknown("cannot tell");
        assertThat(p.known()).isFalse();
        assertThat(p.untrusted()).isTrue();
        assertThat(p.privateData()).isTrue();
        assertThat(p.outward()).isTrue();
        assertThat(p.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(p.note()).isEqualTo("cannot tell");
    }

    @Test
    void journaledAndIsolationAreSetWithoutChangingTheRest() {
        ToolProfile p =
                ToolProfile.of(false, true, true, true, "runs")
                        .journaled(true)
                        .isolated(Isolation.PROCESS);
        assertThat(p.journaled()).isTrue();
        assertThat(p.isolation()).isEqualTo(Isolation.PROCESS);
        assertThat(p.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(p.note()).isEqualTo("runs");
    }

    @Test
    void theFactsCannotBeMissing() {
        assertThatThrownBy(() -> ToolProfile.of(false, false, false, false, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("note");
        assertThatThrownBy(
                        () ->
                                new ToolProfile(
                                        false,
                                        false,
                                        false,
                                        null,
                                        Isolation.NONE,
                                        false,
                                        true,
                                        "n"))
                .hasMessageContaining("effect");
        assertThatThrownBy(
                        () ->
                                new ToolProfile(
                                        false,
                                        false,
                                        false,
                                        TaskEffect.NONE,
                                        null,
                                        false,
                                        true,
                                        "n"))
                .hasMessageContaining("isolation");
    }

    @Test
    void aKindThatDoesNotAnswerIsAssumedToReachEverything() {
        ToolKind silent =
                new ToolKind() {
                    @Override
                    public String name() {
                        return "mystery";
                    }

                    @Override
                    public String check(Map<String, String> options, Path baseDir) {
                        return null;
                    }

                    @Override
                    public Tool create(String name, Map<String, String> options, Path baseDir) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Tool create(
                            String name,
                            Map<String, String> options,
                            Path baseDir,
                            EffectContext context) {
                        throw new UnsupportedOperationException();
                    }
                };
        ToolProfile p = silent.profile(Map.of());
        assertThat(p.known()).isFalse();
        assertThat(p.outward()).isTrue();
        assertThat(p.note()).contains("mystery").contains("does not say what it reaches");
    }
}
