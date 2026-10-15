package io.github.llm4j.model;

/**
 * A piece of a tool call that is still being written, on a streamed chunk. Chunks with the same {@code index} belong to one call: the first
 * has the {@code name} (and the {@code id} when the provider sends one), the rest carry {@code argumentsFragment}, which are consecutive
 * slices of the arguments' JSON text. Join the fragments, then parse; until the stream ends the text may not be valid JSON. A provider
 * that sends a call whole (Gemini) sends it as one delta.
 *
 * @param index which call, counting from 0 in the order the provider started them
 * @param id the provider's id for the call; null after the first delta, and always null for providers that send none
 * @param name the tool's name; null after the first delta
 * @param argumentsFragment the next slice of the arguments' JSON text; may be empty
 */
public record ToolCallDelta(int index, String id, String name, String argumentsFragment) {

    public ToolCallDelta {
        argumentsFragment = argumentsFragment == null ? "" : argumentsFragment;
    }
}
