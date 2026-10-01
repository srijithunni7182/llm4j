package io.github.llm4j.eval.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

/**
 * Round-2 calibration study on the harder dataset ({@link CalibrationDatasetV2}). Opt-in: needs
 * {@code EVAL4J_ANTHROPIC_API_KEY}; writes {@code target/calibration-round2.md} (override the file
 * with {@code EVAL4J_CALIBRATION_OUT}). Never fails on a threshold miss.
 */
class CalibrationStudyV2IntegrationTest {

    @Test
    void runRound2() throws Exception {
        String key = System.getenv("EVAL4J_ANTHROPIC_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "EVAL4J_ANTHROPIC_API_KEY not set");
        String out =
                System.getenv()
                        .getOrDefault("EVAL4J_CALIBRATION_OUT", "target/calibration-round2.md");
        CalibrationRunner.execute(
                CalibrationDatasetV2.dataset(),
                "eval4j calibration study - round 2 (harder dataset)",
                out,
                false);
    }
}
