package io.github.llm4j.loom.execution;

/**
 * What one agent used during a run, from the model calls that succeeded.
 *
 * @param calls successful model calls
 * @param promptTokens tokens sent
 * @param completionTokens tokens written
 */
public record AgentUsage(long calls, long promptTokens, long completionTokens) {}
