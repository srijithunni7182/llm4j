package io.github.llm4j.eval.export;

import io.github.llm4j.eval.dataset.EvalScenario;

/**
 * Stable identifiers that survive across runs, JVMs and machines (run bundle format, FMT §3.1).
 *
 * <pre>
 * caseId = "c_" + hex16(sha256("case" + 0x00 + caseKey))
 * key    = "k_" + hex16(sha256(caseKey + 0x00 + metricId + 0x00 + occurrence))
 * </pre>
 */
public final class CaseKey {

    private CaseKey() {}

    /** The case key of a scenario: its id, else its name, else a hash of its input. */
    public static String of(EvalScenario scenario) {
        if (scenario.id() != null && !scenario.id().isBlank()) {
            return scenario.id();
        }
        if (scenario.name() != null && !scenario.name().isBlank()) {
            return scenario.name();
        }
        String input = scenario.input() == null ? "" : scenario.input();
        return Hashes.sha256Hex(input).substring(0, 12);
    }

    /** The case key of a plain test: {@code <suite>#<method>[<displayName>]}. */
    public static String ofTest(String suite, String testName) {
        return (suite == null ? "" : suite) + "#" + (testName == null ? "" : testName);
    }

    public static String caseId(String caseKey) {
        return "c_" + Hashes.hex16("case\u0000" + caseKey);
    }

    public static String key(String caseKey, String metricId, int occurrence) {
        return "k_" + Hashes.hex16(caseKey + "\u0000" + metricId + "\u0000" + occurrence);
    }
}
