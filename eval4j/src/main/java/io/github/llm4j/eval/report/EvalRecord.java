package io.github.llm4j.eval.report;

import java.util.List;

/**
 * One judged evaluation, as captured by {@link EvalRecorder}. {@code suite}/{@code testName} are
 * {@code null} when the evaluation ran outside a JUnit test that applies {@link
 * EvalReportExtension}. {@code timestamp} is ISO-8601. The case fields ({@code input} onwards) are
 * {@code null} when the evaluating code did not supply them; they feed the dashboard's drill-down.
 */
public record EvalRecord(
        String suite,
        String testName,
        String metric,
        double score,
        double threshold,
        boolean passed,
        String reason,
        String judgeIdentifier,
        String timestamp,
        String input,
        String actualOutput,
        String expectedOutput,
        List<String> retrievalContext,
        Long durationMs) {

    /** A record without case details (also the shape of reports written before the dashboard). */
    public EvalRecord(
            String suite,
            String testName,
            String metric,
            double score,
            double threshold,
            boolean passed,
            String reason,
            String judgeIdentifier,
            String timestamp) {
        this(
                suite,
                testName,
                metric,
                score,
                threshold,
                passed,
                reason,
                judgeIdentifier,
                timestamp,
                null,
                null,
                null,
                null,
                null);
    }
}
