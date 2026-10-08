package io.github.llm4j.eval.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Round-1 calibration study (easy dataset). Opt-in: needs {@code EVAL4J_ANTHROPIC_API_KEY}; writes
 * {@code target/calibration-round1.md}. Never fails on a threshold miss - the report is the
 * deliverable. See {@link CalibrationRunner} for the caveat about author-labelled data.
 */
@Tag("integration")
class CalibrationStudyIntegrationTest {

    @Test
    void runRound1() throws Exception {
        String key = System.getenv("EVAL4J_ANTHROPIC_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "EVAL4J_ANTHROPIC_API_KEY not set");
        CalibrationRunner.execute(
                CalibrationDatasetV1.dataset(),
                "eval4j calibration study - round 1 (easy dataset)",
                "target/calibration-round1.md",
                true);
    }
}
