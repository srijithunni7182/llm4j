package io.github.llm4j.evalreport.format.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The header of a run ({@code run.json}). Environment, profile and summary are kept as trees: the
 * report shows them, it does not interpret most of them.
 */
public record RunMeta(
        String runId,
        String groupId,
        Integer runNumber,
        String status,
        String startedAt,
        String endedAt,
        String project,
        String branch,
        String commit,
        JsonNode source,
        JsonNode profile,
        JsonNode env,
        List<MetricDef> metrics,
        JsonNode summary) {}
