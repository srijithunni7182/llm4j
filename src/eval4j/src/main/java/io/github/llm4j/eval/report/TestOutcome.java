package io.github.llm4j.eval.report;

/**
 * How one JUnit test ended, as seen by {@link EvalReportExtension}. {@code status} is {@code
 * PASSED}, {@code FAILED} or {@code ABORTED}; {@code message} is the failure message, if any.
 */
public record TestOutcome(
        String suite, String testName, String status, long durationMs, String message) {

    public boolean passed() {
        return "PASSED".equals(status);
    }
}
