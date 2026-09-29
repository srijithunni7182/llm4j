package io.github.llm4j.eval.report;

/**
 * One judged evaluation, as captured by {@link EvalRecorder}. {@code suite}/{@code testName} are
 * {@code null} when the evaluation ran outside a JUnit test that applies {@link
 * EvalReportExtension}. {@code timestamp} is ISO-8601.
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
        String timestamp) {}
