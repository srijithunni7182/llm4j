package io.github.llm4j.eval.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JudgeCacheKeyTest {

    @Test
    void compute_isStableForIdenticalInputs() {
        String key1 =
                JudgeCacheKey.compute(
                        "Correctness",
                        "criteria",
                        "input",
                        "expected",
                        List.of("c1"),
                        List.of("r1"),
                        "actual",
                        "trajectory",
                        0.0,
                        "judge-v1");
        String key2 =
                JudgeCacheKey.compute(
                        "Correctness",
                        "criteria",
                        "input",
                        "expected",
                        List.of("c1"),
                        List.of("r1"),
                        "actual",
                        "trajectory",
                        0.0,
                        "judge-v1");

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void compute_differsWhenActualOutputDiffers() {
        String key1 =
                JudgeCacheKey.compute(
                        "Correctness", "criteria", null, null, null, null, "36", null, 0.0, null);
        String key2 =
                JudgeCacheKey.compute(
                        "Correctness", "criteria", null, null, null, null, "42", null, 0.0, null);

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void compute_differsWhenCriteriaDiffers() {
        String key1 =
                JudgeCacheKey.compute(
                        "Correctness",
                        "criteria one",
                        null,
                        null,
                        null,
                        null,
                        "36",
                        null,
                        0.0,
                        null);
        String key2 =
                JudgeCacheKey.compute(
                        "Correctness",
                        "criteria two",
                        null,
                        null,
                        null,
                        null,
                        "36",
                        null,
                        0.0,
                        null);

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void compute_toleratesAllNullOptionalFields() {
        String key =
                JudgeCacheKey.compute(
                        "Correctness", "criteria", null, null, null, null, "36", null, 0.0, null);
        assertThat(key).isNotBlank();
    }

    @Test
    void compute_producesA64CharacterHexSha256Digest() {
        String key =
                JudgeCacheKey.compute(
                        "n", "c", "i", "e", List.of(), List.of(), "a", null, 0.0, null);
        assertThat(key).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void compute_differsWhenTemperatureDiffers() {
        String key1 =
                JudgeCacheKey.compute(
                        "Correctness", "criteria", null, null, null, null, "36", null, 0.0, null);
        String key2 =
                JudgeCacheKey.compute(
                        "Correctness", "criteria", null, null, null, null, "36", null, 0.7, null);

        assertThat(key1)
                .as(
                        "a samples=1 draw (temperature 0.0) must not collide with a samples>1 draw (0.7)")
                .isNotEqualTo(key2);
    }

    @Test
    void compute_differsWhenJudgeIdentifierDiffers() {
        String key1 =
                JudgeCacheKey.compute(
                        "Correctness",
                        "criteria",
                        null,
                        null,
                        null,
                        null,
                        "36",
                        null,
                        0.0,
                        "gemini-2.5-pro");
        String key2 =
                JudgeCacheKey.compute(
                        "Correctness",
                        "criteria",
                        null,
                        null,
                        null,
                        null,
                        "36",
                        null,
                        0.0,
                        "gpt-4o");

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void compute_differsWhenTrajectoryDiffers() {
        String key1 =
                JudgeCacheKey.compute(
                        "Correctness",
                        "criteria",
                        null,
                        null,
                        null,
                        null,
                        "36",
                        "step 1",
                        0.0,
                        null);
        String key2 =
                JudgeCacheKey.compute(
                        "Correctness",
                        "criteria",
                        null,
                        null,
                        null,
                        null,
                        "36",
                        "step 2",
                        0.0,
                        null);

        assertThat(key1).isNotEqualTo(key2);
    }
}
