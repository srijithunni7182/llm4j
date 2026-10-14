package io.github.llm4j.context;

import java.math.BigDecimal;

/**
 * What a strategy spent on model calls of its own (a summary, for instance).
 *
 * @param calls model calls made
 * @param promptTokens tokens sent
 * @param completionTokens tokens received
 * @param cost the cost, or null when unpriced
 */
public record CompactionUsage(int calls, long promptTokens, long completionTokens, BigDecimal cost) {}
