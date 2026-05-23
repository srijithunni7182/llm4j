package io.github.llm4j.tantrik.console.model;

/**
 * Response body for POST /api/generate/loom.
 *
 * @param script       The generated Loom DSL script text.
 * @param workflowName The primary workflow name detected in the generated script.
 */
public record GenerateLoomResponse(String script, String workflowName) {}
