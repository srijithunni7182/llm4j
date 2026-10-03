package io.github.llm4j.eval.export;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * One line of {@code evaluations.jsonl}: one judged, asserted, measured or compared evaluation of
 * one case. Fields left {@code null} are not written.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Evaluation(
        Integer seq,
        String key,
        String caseId,
        String scenarioId,
        String testId,
        String metric,
        Kind kind,
        EvalStatus status,
        Double score,
        Double threshold,
        Boolean passed,
        String reason,
        String display,
        Map<String, Object> measured,
        String family,
        String facet,
        String dimension,
        Source source,
        String evaluatedInRun,
        String evaluatedAt,
        String judgeId,
        List<Double> samples,
        Long durationMs,
        Integer calls,
        Integer tokensIn,
        Integer tokensOut,
        Double costUsd,
        String input,
        String actualOutput,
        String expectedOutput,
        List<String> retrievalContext,
        String traceId,
        String timestamp) {

    public static Builder builder(MetricRef metric) {
        return new Builder(metric);
    }

    /** Fluent construction; {@link EvalRun} fills in seq, key, ids and timestamp. */
    public static final class Builder {
        private final MetricRef metric;
        private EvalStatus status = EvalStatus.EVALUATED;
        private Double score;
        private Double threshold;
        private Boolean passed;
        private String reason;
        private String display;
        private Map<String, Object> measured;
        private Source source = Source.FRESH;
        private String judgeId;
        private List<Double> samples;
        private Long durationMs;
        private Integer calls;
        private Integer tokensIn;
        private Integer tokensOut;
        private Double costUsd;
        private String input;
        private String actualOutput;
        private String expectedOutput;
        private List<String> retrievalContext;
        private String traceId;

        private Builder(MetricRef metric) {
            this.metric = metric;
            this.threshold = metric.threshold();
        }

        public Builder status(EvalStatus v) {
            this.status = v;
            return this;
        }

        public Builder score(Double v) {
            this.score = v;
            return this;
        }

        public Builder threshold(Double v) {
            this.threshold = v;
            return this;
        }

        public Builder passed(Boolean v) {
            this.passed = v;
            return this;
        }

        public Builder reason(String v) {
            this.reason = v;
            return this;
        }

        public Builder display(String v) {
            this.display = v;
            return this;
        }

        public Builder measured(double value, String unit, Double budget) {
            this.measured = new java.util.LinkedHashMap<>();
            this.measured.put("value", value);
            if (unit != null) {
                this.measured.put("unit", unit);
            }
            if (budget != null) {
                this.measured.put("budget", budget);
            }
            return this;
        }

        public Builder source(Source v) {
            this.source = v;
            return this;
        }

        public Builder judgeId(String v) {
            this.judgeId = v;
            return this;
        }

        public Builder samples(List<Double> v) {
            this.samples = v;
            return this;
        }

        public Builder durationMs(Long v) {
            this.durationMs = v;
            return this;
        }

        public Builder usage(Integer calls, Integer tokensIn, Integer tokensOut, Double costUsd) {
            this.calls = calls;
            this.tokensIn = tokensIn;
            this.tokensOut = tokensOut;
            this.costUsd = costUsd;
            return this;
        }

        public Builder details(
                String input,
                String actualOutput,
                String expectedOutput,
                List<String> retrievalContext) {
            this.input = input;
            this.actualOutput = actualOutput;
            this.expectedOutput = expectedOutput;
            this.retrievalContext = retrievalContext;
            return this;
        }

        public Builder traceId(String v) {
            this.traceId = v;
            return this;
        }

        MetricRef metric() {
            return metric;
        }

        Evaluation build(
                int seq,
                String key,
                String caseId,
                String scenarioId,
                String testId,
                String timestamp) {
            Boolean p = passed;
            if (status == EvalStatus.EVALUATED && p == null && score != null && threshold != null) {
                p = score >= threshold;
            }
            return new Evaluation(
                    seq,
                    key,
                    caseId,
                    scenarioId,
                    testId,
                    metric.id(),
                    metric.kind(),
                    status,
                    score,
                    threshold,
                    status == EvalStatus.EVALUATED ? p : null,
                    reason,
                    display,
                    measured,
                    null,
                    null,
                    null,
                    source,
                    null,
                    timestamp,
                    judgeId,
                    samples,
                    durationMs,
                    calls,
                    tokensIn,
                    tokensOut,
                    costUsd,
                    input,
                    actualOutput,
                    expectedOutput,
                    retrievalContext,
                    traceId,
                    timestamp);
        }
    }
}
