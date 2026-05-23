package io.github.llm4j.tantrik.console.model;

/**
 * Per-agent token consumption statistics for a single workflow run.
 *
 * @param agentName          name of the agent (parsed from the trace event message)
 * @param inputTokens        total estimated input tokens consumed by this agent
 * @param squeezedCount      number of turns where context compression was applied
 * @param avgCompressionRatio average compression ratio across squeezed turns (0.0 if none)
 */
public record AgentTokenStat(
        String agentName,
        int inputTokens,
        int squeezedCount,
        double avgCompressionRatio
) {}
