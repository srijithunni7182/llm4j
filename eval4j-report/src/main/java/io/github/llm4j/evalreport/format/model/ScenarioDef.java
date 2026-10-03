package io.github.llm4j.evalreport.format.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioDef(
        String id,
        String caseId,
        String datasetId,
        String dataset,
        String name,
        String input,
        String expectedOutput,
        List<String> expectedTools,
        List<String> dimensions,
        List<String> families,
        List<String> tags) {}
