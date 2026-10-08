package io.github.llm4j.evalreport.format.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TestOutcome(
        String testId,
        String suite,
        String name,
        String caseId,
        String scenarioId,
        String status,
        Long durationMs,
        String message) {}
