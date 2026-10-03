package io.github.llm4j.evalreport.format.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MetricDef(
        String id,
        String name,
        String kind,
        String family,
        String facet,
        String dimension,
        Double threshold,
        String unit,
        Double budget,
        String judgeId) {}
