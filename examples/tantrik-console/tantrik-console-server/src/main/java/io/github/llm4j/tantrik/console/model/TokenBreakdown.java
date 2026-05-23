package io.github.llm4j.tantrik.console.model;

import java.util.List;

/**
 * Aggregated token consumption breakdown for a single workflow run.
 * Returned by {@code GET /api/runs/{runId}/tokens}.
 *
 * @param totalInputTokens total estimated input tokens across all agents in the run
 * @param agentBreakdown   per-agent token statistics
 * @param runDurationMs    wall-clock duration of the run in milliseconds
 */
public record TokenBreakdown(
        int totalInputTokens,
        List<AgentTokenStat> agentBreakdown,
        long runDurationMs
) {}
