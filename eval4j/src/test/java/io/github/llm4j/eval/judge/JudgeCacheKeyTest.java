package io.github.llm4j.eval.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JudgeCacheKeyTest {

    @Test
    void compute_isStableForIdenticalInputs() {
        String key1 =
                JudgeCacheKey.compute(
                        "Correctness", "criteria", "input", "expected", List.of("c1"), List.of("r1"), "actual");
        String key2 =
                JudgeCacheKey.compute(
                        "Correctness", "criteria", "input", "expected", List.of("c1"), List.of("r1"), "actual");

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void compute_differsWhenActualOutputDiffers() {
        String key1 = JudgeCacheKey.compute("Correctness", "criteria", null, null, null, null, "36");
        String key2 = JudgeCacheKey.compute("Correctness", "criteria", null, null, null, null, "42");

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void compute_differsWhenCriteriaDiffers() {
        String key1 = JudgeCacheKey.compute("Correctness", "criteria one", null, null, null, null, "36");
        String key2 = JudgeCacheKey.compute("Correctness", "criteria two", null, null, null, null, "36");

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void compute_toleratesAllNullOptionalFields() {
        String key = JudgeCacheKey.compute("Correctness", "criteria", null, null, null, null, "36");
        assertThat(key).isNotBlank();
    }

    @Test
    void compute_producesA64CharacterHexSha256Digest() {
        String key = JudgeCacheKey.compute("n", "c", "i", "e", List.of(), List.of(), "a");
        assertThat(key).hasSize(64).matches("[0-9a-f]+");
    }
}
