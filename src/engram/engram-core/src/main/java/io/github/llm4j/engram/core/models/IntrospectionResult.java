package io.github.llm4j.engram.core.models;

import java.util.List;

/**
 * Represents the outcome of an introspective review.
 *
 * @param extractedMemories New memories, insights, or error patterns to store.
 * @param memoriesToDelete Exact content strings of bad/misleading memories that should be pruned.
 */
public record IntrospectionResult(
    List<MemoryEvent> extractedMemories,
    List<String> memoriesToDelete
) {}
