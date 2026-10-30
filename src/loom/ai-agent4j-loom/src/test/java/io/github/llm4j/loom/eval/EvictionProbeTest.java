package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A context-eviction probe: plant a fact early in a long run on a small window and check what the
 * context manager does when the window fills. The mechanism must remove the oldest material first,
 * keep the task, and tell the model that something was removed, so that no conclusion silently
 * rests on material that is gone. It calls no model.
 */
class EvictionProbeTest {

    private static EvictionProbe.Spec spec(String strategy, int window) {
        return new EvictionProbe.Spec(strategy, window, "the vault code is 4417", 2, 30, 800, 2);
    }

    @Test
    void theOldestMaterialGoesFirstTheTaskStaysAndTheModelIsTold() {
        EvictionProbe.Result r = EvictionProbe.run(spec("drop_oldest", 4_000));

        assertThat(r.evictedAtTurn()).isNotNull().isGreaterThan(2);
        assertThat(r.oldestFirst()).isTrue();
        assertThat(r.taskKept()).isTrue();
        assertThat(r.modelTold()).isTrue();
        assertThat(r.problems()).isEmpty();
        assertThat(r.ok()).isTrue();
        assertThat(r.keptFrom()).isGreaterThan(2);
    }

    @Test
    void aPlantedFactThatIsLaterThanTheEvictionPointSurvivesIt() {
        EvictionProbe.Result r = EvictionProbe.run(new EvictionProbe.Spec("drop_oldest", 4_000, "the vault code is 4417", 29, 30, 800, 2));

        assertThat(r.evictedAtTurn()).isNull();
        assertThat(r.problems()).anyMatch(p -> p.contains("fact") && p.contains("never evicted"));
    }

    @Test
    void aWindowThatNeverFillsProvesNothingAndSaysSo() {
        EvictionProbe.Result r = EvictionProbe.run(spec("drop_oldest", 1_000_000));

        assertThat(r.evictedAtTurn()).isNull();
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("never evicted"));
    }

    @Test
    void theLinesSayWhenTheFactWentAndWhatSurvived() {
        EvictionProbe.Spec spec = spec("drop_oldest", 4_000);

        String text = String.join("\n", EvictionProbe.lines(spec, EvictionProbe.run(spec)));

        assertThat(text).contains("planted at turn 2").contains("evicted at turn").contains("oldest first").contains("the model was told").contains("task kept");
    }

    @Test
    void aStrategyThatNeedsAModelIsRefusedBecauseTheProbeCallsNone() {
        assertThatThrownBy(() -> EvictionProbe.run(spec("summarize", 4_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summarize")
                .hasMessageContaining("needs a model");
    }

    @Test
    void anUnknownStrategyListsTheKnownOnes() {
        assertThatThrownBy(() -> EvictionProbe.run(spec("sideways", 4_000))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sideways").hasMessageContaining("drop_oldest");
    }

    @Test
    void aStrategyThatRemovesNothingIsCaughtByTheProbe() {
        EvictionProbe.Result r = EvictionProbe.run(spec("none", 4_000));

        assertThat(r.evictedAtTurn()).isNull();
        assertThat(r.ok()).isFalse();
    }

    @Test
    void theSpecIsCheckedAtTheBoundary() {
        assertThatThrownBy(() -> new EvictionProbe.Spec("drop_oldest", 4_000, " ", 2, 30, 800, 2)).hasMessageContaining("fact");
        assertThatThrownBy(() -> new EvictionProbe.Spec("drop_oldest", 4_000, "x", 31, 30, 400, 2)).hasMessageContaining("turn");
        assertThatThrownBy(() -> new EvictionProbe.Spec("drop_oldest", 10, "x", 2, 30, 800, 2)).hasMessageContaining("window");
        assertThatThrownBy(() -> new EvictionProbe.Spec("drop_oldest", 4_000, "x", 2, 100_000, 400, 2)).hasMessageContaining("turns");
    }

    /** Drops whole exchanges from the given end until the prompt fits, and says nothing. */
    private static io.github.llm4j.context.ContextCompactor silent(boolean oldestFirst) {
        return (messages, request) -> {
            java.util.List<io.github.llm4j.model.Message> kept = new java.util.ArrayList<>(messages);
            while (request.sizeOf(kept) > request.targetTokens() && kept.size() > 4) {
                int at = oldestFirst ? 1 : kept.size() - 3;
                kept.remove(at);
                kept.remove(at);
            }
            return io.github.llm4j.context.CompactionResult.of("silent", messages, kept, request);
        };
    }

    @Test
    void aStrategyThatDropsMaterialWithoutTellingTheModelIsCaught() {
        EvictionProbe.Result r = EvictionProbe.run(spec("drop_oldest", 4_000), silent(true));

        assertThat(r.evictedAtTurn()).isNotNull();
        assertThat(r.modelTold()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("nothing tells the model"));
        assertThat(r.ok()).isFalse();
    }

    @Test
    void aStrategyThatDropsTheNewestMaterialFirstIsCaught() {
        EvictionProbe.Result r = EvictionProbe.run(spec("drop_oldest", 4_000), silent(false));

        assertThat(r.oldestFirst()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("oldest material did not go first"));
    }
}
