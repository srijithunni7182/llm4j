package io.github.llm4j.tantrik.console.model;

/**
 * Request body for POST /api/generate/loom.
 *
 * @param prompt   Natural-language description of the workflow to generate.
 * @param mockMode When true, returns a deterministic template without calling an LLM.
 */
public record GenerateLoomRequest(String prompt, boolean mockMode) {}
