package io.github.llm4j.evalreport.format.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** One line of {@code evaluations.jsonl}. Unknown fields are ignored (FMT-21). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ev(
        Integer seq,
        String key,
        String caseId,
        String scenarioId,
        String testId,
        String metric,
        String kind,
        String status,
        Double score,
        Double threshold,
        Boolean passed,
        String reason,
        String display,
        JsonNode measured,
        String family,
        String facet,
        String dimension,
        String source,
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

    public boolean counted() {
        return "EVALUATED".equals(status) && passed != null;
    }

    public boolean judged() {
        return "JUDGE".equals(kind) || "PAIRWISE".equals(kind);
    }

    public String sourceOrFresh() {
        return source == null ? "FRESH" : source;
    }
}
